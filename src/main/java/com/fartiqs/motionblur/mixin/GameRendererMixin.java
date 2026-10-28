package com.fartiqs.motionblur.mixin;

import com.fartiqs.motionblur.Config;
import com.fartiqs.motionblur.MotionBlurRenderer;
import com.fartiqs.motionblur.Status;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    /** Runs after the 3D world has been fully drawn into the main render target (before the GUI). */
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void fartiqs$motionBlur(DeltaTracker deltaTracker, CallbackInfo ci) {
        MotionBlurRenderer.apply(Minecraft.getInstance());
    }

    /** Rescales the projection horizontally so the world is drawn at the chosen aspect ratio. */
    @Inject(method = "getProjectionMatrix(F)Lorg/joml/Matrix4f;", at = @At("RETURN"), cancellable = true)
    private void fartiqs$aspect(float fov, CallbackInfoReturnable<Matrix4f> cir) {
        Status.aspectHookCalls++;
        Config cfg = Config.get();
        if (!cfg.aspectEnabled) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            float windowAspect = (float) mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
            float target = Math.max(0.1f, cfg.aspect);
            float factor = windowAspect / target;
            Matrix4f m = new Matrix4f(cir.getReturnValue());
            m.scaleLocal(factor, 1.0f, 1.0f);
            cir.setReturnValue(m);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
