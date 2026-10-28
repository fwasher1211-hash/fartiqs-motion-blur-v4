package com.fartiqs.motionblur;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

public class FartiqsMotionBlur implements ClientModInitializer {
    private static KeyMapping openMenu;

    @Override
    public void onInitializeClient() {
        Config.load();

        KeyMapping.Category category = KeyMapping.Category.register(
                ResourceLocation.fromNamespaceAndPath("fartiqsmotionblur", "main"));
        openMenu = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.fartiqsmotionblur.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PERIOD, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenu.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new MotionBlurScreen(null));
                }
            }
        });
    }
}
