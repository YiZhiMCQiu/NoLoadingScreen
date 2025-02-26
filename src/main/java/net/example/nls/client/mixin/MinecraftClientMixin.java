package net.example.nls.client.mixin;

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.QuickPlayLogger;
import net.minecraft.client.RunArgs;
import net.minecraft.client.gui.screen.Overlay;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.session.Session;
import net.minecraft.client.session.report.ReporterEnvironment;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.resource.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.QueueingWorldGenerationProgressListener;
import net.minecraft.server.SaveLoader;
import net.minecraft.server.WorldGenerationProgressTracker;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.ApiServices;
import net.minecraft.util.Unit;
import net.minecraft.util.UserCache;
import net.minecraft.util.Util;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.CrashReportSection;
import net.minecraft.world.level.storage.LevelStorage;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Unique
    private static final Text DATA_READ_TEXT = Text.translatable("selectWorld.data_read");

    @Unique private static ResourceReload reload;

    @Shadow @Final private AtomicReference<WorldGenerationProgressTracker> worldGenProgressTracker;

    @Shadow @Final private YggdrasilAuthenticationService authenticationService;

    @Shadow @Final public File runDirectory;

    @Shadow private @Nullable IntegratedServer server;

    @Shadow @Final private Queue<Runnable> renderTaskQueue;

    @Shadow private boolean integratedServerRunning;

    @Shadow public abstract void ensureAbuseReportContext(ReporterEnvironment environment);

    @Shadow @Final private QuickPlayLogger quickPlayLogger;

    @Shadow public abstract Session getSession();

    @Shadow private @Nullable ClientConnection integratedServerConnection;

    @Shadow @Nullable public Screen currentScreen;

    @Mutable @Shadow @Final private ReloadableResourceManagerImpl resourceManager;

    @Shadow @Final private static CompletableFuture<Unit> COMPLETED_UNIT_FUTURE;

    @Inject(at = @At("HEAD"), method = "setOverlay", cancellable = true)
    private void setOverlay(Overlay overlay, CallbackInfo ci) {
        if (overlay instanceof SplashOverlay splashOverlay) {
            MinecraftClient client = (MinecraftClient) (Object) this;
            reload = splashOverlay.reload;
            client.onFinishedLoading(null);
            ci.cancel();
        }
    }

    @Inject(at = @At("RETURN"), method = "isFinishedLoading", cancellable = true)
    private void isFinishedLoading(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/WindowProvider;createWindow(Lnet/minecraft/client/WindowSettings;Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/client/util/Window;"))
    private void beforeCreateWindow(RunArgs args, CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        this.resourceManager = new ReloadableResourceManagerImpl(ResourceType.CLIENT_RESOURCES);
        this.resourceManager.reload(Util.getMainWorkerExecutor(), client, COMPLETED_UNIT_FUTURE, List.of());
    }

    @Inject(at = @At("HEAD"), method = "setScreen", cancellable = true)
    private void setScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof LevelLoadingScreen) {
            ci.cancel();
        }
        if (screen instanceof TitleScreen && this.currentScreen instanceof TitleScreen) {
            ci.cancel();
        }
    }

    @Inject(at = @At("HEAD"), method = "setScreenAndRender", cancellable = true)
    private void setScreenAndRender(Screen screen, CallbackInfo ci) {
        if (screen.getTitle().getString().equals(DATA_READ_TEXT.getString())) ci.cancel();
    }

    @Redirect(method = "reset", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;setScreen(Lnet/minecraft/client/gui/screen/Screen;)V"))
    private void setScreen(MinecraftClient instance, Screen screen) {
        instance.setScreen(new TitleScreen());
    }

    @Inject(at = @At("HEAD"), method = "startIntegratedServer", cancellable = true)
    private void startIntegratedServer(LevelStorage.Session session, ResourcePackManager dataPackManager, SaveLoader saveLoader, boolean newWorld, CallbackInfo ci) {
        new Thread(() -> {
            var self = (MinecraftClient) (Object) this;
            this.worldGenProgressTracker.set(null);
            Instant instant = Instant.now();

            try {
                session.backupLevelDataFile(saveLoader.combinedDynamicRegistries().getCombinedRegistryManager(), saveLoader.saveProperties());
                ApiServices apiServices = ApiServices.create(this.authenticationService, this.runDirectory);
                apiServices.userCache().setExecutor(self);
                SkullBlockEntity.setServices(apiServices, self);
                UserCache.setUseRemote(false);
                this.server = MinecraftServer.startServer(
                        thread -> new IntegratedServer(thread, self, session, dataPackManager, saveLoader, apiServices, spawnChunkRadius -> {
                            WorldGenerationProgressTracker worldGenerationProgressTracker = WorldGenerationProgressTracker.create(spawnChunkRadius + 0);
                            this.worldGenProgressTracker.set(worldGenerationProgressTracker);
                            return QueueingWorldGenerationProgressListener.create(worldGenerationProgressTracker, this.renderTaskQueue::add);
                        })
                );
                this.integratedServerRunning = true;
                this.ensureAbuseReportContext(ReporterEnvironment.ofIntegratedServer());
                this.quickPlayLogger.setWorld(QuickPlayLogger.WorldType.SINGLEPLAYER, session.getDirectoryName(), saveLoader.saveProperties().getLevelName());
            } catch (Throwable var11) {
                CrashReport crashReport = CrashReport.create(var11, "Starting integrated server");
                CrashReportSection crashReportSection = crashReport.addElement("Starting integrated server");
                crashReportSection.add("Level ID", session.getDirectoryName());
                crashReportSection.add("Level Name", () -> saveLoader.saveProperties().getLevelName());
                throw new CrashException(crashReport);
            }
            Duration duration = Duration.between(instant, Instant.now());
            SocketAddress socketAddress = this.server.getNetworkIo().bindLocal();
            ClientConnection clientConnection = ClientConnection.connectLocal(socketAddress);
            clientConnection.connect(socketAddress.toString(), 0, new ClientLoginNetworkHandler(clientConnection, self, null, null, newWorld, duration, status -> {
            }, null));
            clientConnection.send(new LoginHelloC2SPacket(this.getSession().getUsername(), this.getSession().getUuidOrNull()));
            this.integratedServerConnection = clientConnection;
        }).start();
        ci.cancel();
    }
}
