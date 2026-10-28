package com.fartiqs.motionblur;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.nio.ByteBuffer;

/**
 * Frame-accumulation motion blur.
 *
 * Every frame: draw last frame's picture (history) over the freshly rendered world with
 * alpha = persistence, then copy the result into the history texture.
 *
 * The previous version queried "the currently bound framebuffer", which in 1.21.5+ is not the
 * main game framebuffer at this point. Here we take the main colour texture straight from the
 * main RenderTarget and attach it to our own FBO, so we always touch the right image.
 */
public final class MotionBlurRenderer {
    private static final String VERT = """
            #version 150
            out vec2 uv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 150
            in vec2 uv;
            out vec4 o;
            uniform sampler2D tex;
            uniform float alpha;
            void main() { o = vec4(texture(tex, uv).rgb, alpha); }
            """;

    private static boolean initialised;
    private static boolean failed;
    private static int program, vao, uAlpha;
    private static int mainFbo, histFbo, histTex, histW, histH;
    private static boolean hasHistory;

    private static long lastNanos;
    private static float lastYaw, lastPitch, adaptSmooth;
    private static boolean hasLastRot;

    private MotionBlurRenderer() {}

    /** Called when the user toggles the effect, so a past error can be retried. */
    public static void retry() {
        failed = false;
        hasHistory = false;
        Status.blur = "RETRYING";
    }

    public static void apply(Minecraft mc) {
        Status.blurHookCalls++;
        if (failed) return;
        Config cfg = Config.get();
        try {
            boolean inOwnMenu = mc.screen instanceof MotionBlurScreen;
            if (!cfg.enabled || mc.level == null
                    || (cfg.disableInMenus && mc.screen != null && !inOwnMenu)) {
                hasHistory = false;
                hasLastRot = false;
                lastNanos = 0;
                Status.blur = cfg.enabled ? "PAUSED" : "OFF";
                return;
            }

            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1f / 60f : (now - lastNanos) / 1.0e9f;
            lastNanos = now;
            dt = Mth.clamp(dt, 1f / 240f, 1f / 15f);

            float amount = Mth.clamp(cfg.strength / 100f, 0f, 1f) * 0.93f;

            Camera cam = mc.gameRenderer.getMainCamera();
            float yaw = cam.getYRot();
            float pitch = cam.getXRot();
            if (cfg.adaptive) {
                float turn = 0f;
                if (hasLastRot) {
                    turn = (Math.abs(Mth.wrapDegrees(yaw - lastYaw)) + Math.abs(pitch - lastPitch)) / (dt * 60f);
                }
                float threshold = Mth.lerp(Mth.clamp(cfg.adaptiveSensitivity / 100f, 0f, 1f), 14f, 1.5f);
                float target = Mth.clamp(turn / threshold, 0f, 1f);
                adaptSmooth = Mth.lerp(0.4f, adaptSmooth, target);
                amount *= adaptSmooth;
            }
            lastYaw = yaw;
            lastPitch = pitch;
            hasLastRot = true;

            // Keep the look the same at any frame rate.
            float alpha = (float) Math.pow(amount, dt * 60f);

            RenderTarget target = mc.getMainRenderTarget();
            GpuTexture color = target.getColorTexture();
            if (!(color instanceof GlTexture glTex)) {
                throw new IllegalStateException("Game is not using the OpenGL backend ("
                        + (color == null ? "no colour texture" : color.getClass().getSimpleName()) + ")");
            }

            run(glTex.glId(), target.width, target.height, alpha);

            Status.blurFrames++;
            Status.lastAlpha = alpha;
            Status.blur = "ACTIVE";
        } catch (Throwable t) {
            failed = true;
            Status.blur = "ERROR: " + t.getClass().getSimpleName() + " - " + t.getMessage();
            System.err.println("[Fartiqs Motion Blur] Disabled after a rendering error:");
            t.printStackTrace();
        }
    }

    private static void run(int mainTexId, int w, int h, float alpha) {
        for (int i = 0; i < 8 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) { /* flush old errors */ }

        // ---- save every piece of GL state we touch ----
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean wasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int bSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int bDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int bSrcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int bDstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        ByteBuffer mask = BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        try {
            init();
            ensureHistory(w, h);

            // Attach the game's real colour texture to our own FBO.
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, mainTexId, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("main framebuffer attachment incomplete");
            }

            if (hasHistory && alpha > 0.002f) {
                GL11.glViewport(0, 0, w, h);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glColorMask(true, true, true, true);
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);

                GL20.glUseProgram(program);
                GL20.glUniform1f(uAlpha, alpha);
                GL30.glBindVertexArray(vao);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL33.glBindSampler(0, 0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, histTex);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }

            // Copy the blended result into the history texture.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, histFbo);
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            hasHistory = true;

            Status.glError = GL11.glGetError();
        } finally {
            // ---- restore ----
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL33.glBindSampler(0, prevSampler);
            GL13.glActiveTexture(prevActive);
            GL20.glUseProgram(prevProgram);
            GL30.glBindVertexArray(prevVao);
            GL11.glViewport(vp[0], vp[1], vp[2], vp[3]);
            GL11.glColorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
            GL14.glBlendFuncSeparate(bSrcRgb, bDstRgb, bSrcA, bDstA);
            setEnabled(GL11.GL_BLEND, wasBlend);
            setEnabled(GL11.GL_DEPTH_TEST, wasDepth);
            setEnabled(GL11.GL_CULL_FACE, wasCull);
            setEnabled(GL11.GL_SCISSOR_TEST, wasScissor);
        }
    }

    private static void setEnabled(int cap, boolean on) {
        if (on) GL11.glEnable(cap); else GL11.glDisable(cap);
    }

    private static void init() {
        if (initialised) return;
        int vs = compile(GL20.GL_VERTEX_SHADER, VERT);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG);
        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Shader link failed: " + GL20.glGetProgramInfoLog(program, 4096));
        }
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        uAlpha = GL20.glGetUniformLocation(program, "alpha");
        int uTex = GL20.glGetUniformLocation(program, "tex");
        GL20.glUseProgram(program);
        GL20.glUniform1i(uTex, 0);
        vao = GL30.glGenVertexArrays();
        mainFbo = GL30.glGenFramebuffers();
        histFbo = GL30.glGenFramebuffers();
        initialised = true;
    }

    private static int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Shader compile failed: " + GL20.glGetShaderInfoLog(s, 4096));
        }
        return s;
    }

    private static void ensureHistory(int w, int h) {
        if (histTex != 0 && histW == w && histH == h) return;
        if (histTex != 0) GL11.glDeleteTextures(histTex);
        histTex = GL11.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, histTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, histFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, histTex, 0);
        histW = w;
        histH = h;
        hasHistory = false;
    }
}
