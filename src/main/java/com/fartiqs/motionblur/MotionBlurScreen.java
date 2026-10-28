package com.fartiqs.motionblur;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Black + neon red settings menu.
 *
 * The real vanilla widgets are still used for clicking/dragging (so input handling stays
 * version-proof) but they are made fully transparent; this class paints all the visuals.
 */
public class MotionBlurScreen extends Screen {
    private static final int NEON = 0xFFFF073A;
    private static final int NEON_DIM = 0xFF8C0A22;
    private static final int NEON_DARK = 0xFF4A0612;
    private static final int BLACK = 0xFF050505;
    private static final int PANEL = 0xFF0B0B0B;
    private static final int FIELD = 0xFF121212;
    private static final int GREY = 0xFF9A9A9A;
    private static final int WHITE = 0xFFF2F2F2;

    private static final int PW = 400, PH = 234, COL_W = 175;

    private final Screen parent;
    private final List<Item> items = new ArrayList<>();
    private Slider aspectSlider;
    private int top, left;

    private record Item(AbstractWidget w, Supplier<String> label, DoubleSupplier value, BooleanSupplier on) {}

    public MotionBlurScreen(Screen parent) {
        super(Component.literal("Fartiqs Motion Blur"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ widgets

    private void addButton(int x, int y, int w, Supplier<String> label, BooleanSupplier on, Runnable action) {
        Button b = Button.builder(Component.empty(), btn -> action.run()).bounds(x, y, w, 20).build();
        b.setAlpha(0f);
        addRenderableWidget(b);
        items.add(new Item(b, label, null, on));
    }

    private Slider addSlider(int x, int y, int w, double initial, Supplier<String> label, DoubleConsumer onChange) {
        Slider s = new Slider(x, y, w, 20, initial, onChange);
        s.setAlpha(0f);
        addRenderableWidget(s);
        items.add(new Item(s, label, s::get, null));
        return s;
    }

    @Override
    protected void init() {
        items.clear();
        Config c = Config.get();
        top = Math.max(2, (this.height - PH) / 2);
        left = (this.width - PW) / 2;
        int lx = left + 15;
        int rx = left + 15 + COL_W + 20;
        int y0 = top + 58;
        int pitch = 24;

        // --- left column: motion blur
        addButton(lx, y0, COL_W, () -> "Motion Blur: " + (c.enabled ? "ON" : "OFF"), () -> c.enabled, () -> {
            c.enabled = !c.enabled;
            MotionBlurRenderer.retry();
        });
        addSlider(lx, y0 + pitch, COL_W, c.strength / 100.0,
                () -> "Strength: " + Math.round(c.strength) + "%",
                v -> c.strength = Math.round((float) v * 100f));
        addButton(lx, y0 + pitch * 2, COL_W, () -> "Adaptive (turn only): " + (c.adaptive ? "ON" : "OFF"),
                () -> c.adaptive, () -> c.adaptive = !c.adaptive);
        addSlider(lx, y0 + pitch * 3, COL_W, c.adaptiveSensitivity / 100.0,
                () -> "Sensitivity: " + Math.round(c.adaptiveSensitivity) + "%",
                v -> c.adaptiveSensitivity = Math.round((float) v * 100f));
        addButton(lx, y0 + pitch * 4, COL_W, () -> "Disable In Menus: " + (c.disableInMenus ? "ON" : "OFF"),
                () -> c.disableInMenus, () -> c.disableInMenus = !c.disableInMenus);

        // --- right column: aspect ratio
        addButton(rx, y0, COL_W, () -> "Custom Aspect: " + (c.aspectEnabled ? "ON" : "OFF"),
                () -> c.aspectEnabled, () -> c.aspectEnabled = !c.aspectEnabled);
        addButton(rx, y0 + pitch, COL_W, () -> "Preset: " + Config.ASPECT_NAMES[c.aspectPreset], null, () -> {
            c.aspectPreset = (c.aspectPreset + 1) % Config.ASPECT_NAMES.length;
            if (c.aspectPreset != Config.CUSTOM) {
                c.aspect = Config.ASPECT_VALUES[c.aspectPreset];
                aspectSlider.set(sliderFromAspect(c.aspect));
            }
        });
        aspectSlider = addSlider(rx, y0 + pitch * 2, COL_W, sliderFromAspect(c.aspect),
                () -> "Ratio: " + String.format("%.2f", c.aspect) + " : 1",
                v -> {
                    c.aspect = Config.MIN_ASPECT + (float) v * (Config.MAX_ASPECT - Config.MIN_ASPECT);
                    c.aspectPreset = Config.CUSTOM;
                });

        // --- bottom row
        int by = top + 204;
        addButton(lx, by, COL_W, () -> "Reset To Defaults", null, () -> {
            Config.reset();
            MotionBlurRenderer.retry();
            this.minecraft.setScreen(new MotionBlurScreen(parent));
        });
        addButton(rx, by, COL_W, () -> "Done", null, this::onClose);
    }

    private static double sliderFromAspect(float a) {
        return Mth.clamp((a - Config.MIN_ASPECT) / (Config.MAX_ASPECT - Config.MIN_ASPECT), 0f, 1f);
    }

    // ------------------------------------------------------------------ drawing

    private static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void glowBorder(GuiGraphics g, int x, int y, int w, int h, int rgb, int layers) {
        for (int i = layers; i >= 1; i--) {
            int a = (int) (0x50 * (1.0 - (i - 1) / (double) layers) * 0.55);
            border(g, x - i, y - i, w + i * 2, h + i * 2, (a << 24) | (rgb & 0xFFFFFF));
        }
    }

    private void centered(GuiGraphics g, String s, int cx, int y, int color) {
        g.drawString(this.font, s, cx - this.font.width(s) / 2, y, color);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Drawn manually in render(); nothing here so vanilla's blur/dim never appears.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // dim the world but keep it visible so the blur can be previewed live
        g.fill(0, 0, this.width, this.height, 0x99000000);

        // panel
        glowBorder(g, left, top, PW, PH, 0xFF073A, 4);
        g.fill(left, top, left + PW, top + PH, PANEL);
        border(g, left, top, PW, PH, NEON);
        g.fill(left + 1, top + 1, left + PW - 1, top + 3, NEON_DARK);

        // title with neon glow
        String title = "FARTIQS MOTION BLUR";
        int tx = left + PW / 2 - this.font.width(title) / 2;
        int ty = top + 10;
        int glow = 0x40FF073A;
        g.drawString(this.font, title, tx - 1, ty, glow);
        g.drawString(this.font, title, tx + 1, ty, glow);
        g.drawString(this.font, title, tx, ty - 1, glow);
        g.drawString(this.font, title, tx, ty + 1, glow);
        g.drawString(this.font, title, tx, ty, NEON);
        g.fill(left + 15, top + 26, left + PW - 15, top + 27, NEON_DIM);
        centered(g, "press . to open this menu", left + PW / 2, top + 31, GREY);

        // column headers
        int lx = left + 15, rx = left + 15 + COL_W + 20;
        g.drawString(this.font, "MOTION BLUR", lx, top + 46, NEON);
        g.drawString(this.font, "ASPECT RATIO", rx, top + 46, NEON);
        g.fill(lx, top + 55, lx + COL_W, top + 56, NEON_DARK);
        g.fill(rx, top + 55, rx + COL_W, top + 56, NEON_DARK);

        // widgets (custom look; the real widgets on top are invisible)
        for (Item it : items) drawItem(g, it, mouseX, mouseY);

        // live status, so the mod can't silently claim it works
        String blur = Status.blur;
        boolean err = blur.startsWith("ERROR");
        String blurLine = "Blur: " + (err ? blur : blur + (Status.blurFrames > 0 && blur.equals("ACTIVE")
                ? "  (mix " + Math.round(Status.lastAlpha * 100) + "%)" : ""));
        String aspLine = "Aspect hook: " + (Status.aspectHookCalls > 0 ? "OK" : "NOT CALLED YET");
        int sy = top + 183;
        g.drawString(this.font, blurLine, lx, sy, err ? NEON : GREY);
        g.drawString(this.font, aspLine, rx, sy, Status.aspectHookCalls > 0 ? GREY : NEON);
        if (Status.glError != 0) {
            g.drawString(this.font, "GL error 0x" + Integer.toHexString(Status.glError), lx, sy + 10, NEON);
        }

        super.render(g, mouseX, mouseY, partialTick); // invisible widgets
    }

    private void drawItem(GuiGraphics g, Item it, int mx, int my) {
        AbstractWidget w = it.w();
        int x = w.getX(), y = w.getY(), ww = w.getWidth(), hh = w.getHeight();
        boolean hover = mx >= x && mx < x + ww && my >= y && my < y + hh;
        boolean on = it.on() != null && it.on().getAsBoolean();

        g.fill(x, y, x + ww, y + hh, on ? 0xFF1C0409 : FIELD);

        if (it.value() != null) { // slider fill + knob
            double v = it.value().getAsDouble();
            int fw = (int) ((ww - 2) * v);
            g.fill(x + 1, y + 1, x + 1 + fw, y + hh - 1, hover ? 0xFF7A0A1E : 0xFF560816);
            g.fill(x + fw, y, x + fw + 2, y + hh, NEON);
        }

        if (hover) glowBorder(g, x, y, ww, hh, 0xFF073A, 2);
        border(g, x, y, ww, hh, hover || on ? NEON : NEON_DIM);

        String label = it.label().get();
        int color = (hover || on) ? WHITE : GREY;
        g.drawString(this.font, label, x + ww / 2 - this.font.width(label) / 2, y + (hh - 8) / 2, color);
    }

    // ------------------------------------------------------------------ closing

    @Override
    public void onClose() {
        Config.save();
        this.minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        Config.save();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ slider

    private static class Slider extends AbstractSliderButton {
        private final DoubleConsumer onChange;

        Slider(int x, int y, int w, int h, double initial, DoubleConsumer onChange) {
            super(x, y, w, h, Component.empty(), initial);
            this.onChange = onChange;
        }

        @Override
        protected void updateMessage() {}

        @Override
        protected void applyValue() {
            onChange.accept(this.value);
        }

        void set(double v) {
            this.value = Mth.clamp(v, 0.0, 1.0);
        }

        double get() {
            return this.value;
        }
    }
}
