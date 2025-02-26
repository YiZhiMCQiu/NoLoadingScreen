package net.example.nls.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameMenuScreen.class)
public class GameMenuScreenMixin {
    @Redirect(method = "disconnect", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;disconnect(Lnet/minecraft/client/gui/screen/Screen;)V"))
    private void disconnect(MinecraftClient instance, Screen disconnectionScreen) {
        instance.disconnect();
    }
}
