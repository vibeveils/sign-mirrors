package dev.signmirrors.gui;

import dev.signmirrors.access.SignEditHost;
import dev.signmirrors.core.MirrorSettings;
import dev.signmirrors.core.MirrorSpec;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Mirror controls added to the sign editor: placement on the left, appearance on the right, the
 * sign shrunk in the middle and the world visible behind, so every change shows live on the real
 * mirror. Changes are written straight into the sign's lines in the compact format
 * ({@link MirrorSettings#toLines}); a sign in the original text format is rewritten on its first change.
 */
public final class MirrorEditor {
    private static final int ROW = 15, H = 14, PANEL = 118, MARGIN = 8, TOP = 22;
    private static final int KEY_ESCAPE = 256;

    private final SignEditHost host;
    private final Font font;
    private final int screenWidth, screenHeight;

    private MirrorSettings settings;
    private boolean keepRatio;
    private double ratio = 1;

    private final List<AbstractWidget> panelWidgets = new ArrayList<>();
    private final List<InputSlider> sliders = new ArrayList<>();
    private final Button[] alignButtons = new Button[9];
    private Button makeButton, ratioButton, frameButton, signButton, materialPrev, materialButton, materialNext;
    /** The material last picked, so cycling the frame mode back to "Material" restores it. */
    private MirrorSettings.Frame lastMaterial = MirrorSettings.Frame.OAK;
    private InputSlider width, height, offX, offY, offZ, yaw, pitch, roll, frameWidth, pixels, opacity, reflectivity;
    private EditBox tintBox;
    private StringWidget swatch, warning, legacyNote;

    private MirrorEditor(SignEditHost host, Font font, int screenWidth, int screenHeight) {
        this.host = host;
        this.font = font;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    /** Builds the controls for the screen that was just initialised. */
    public static MirrorEditor attach(SignEditHost host, Font font, int screenWidth, int screenHeight) {
        MirrorEditor e = new MirrorEditor(host, font, screenWidth, screenHeight);
        e.build();
        e.reload();
        return e;
    }

    /** True while the sign is a mirror (the panels are shown and the editor takes over the layout). */
    public boolean isMirror() {
        return settings != null;
    }

    // =====================================================================================
    // Layout
    // =====================================================================================

    private void build() {
        int left = MARGIN, right = screenWidth - MARGIN - PANEL;
        int y = TOP;

        // ---------------- Placement (left) ----------------
        label(left, y, "placement");
        y += ROW;
        String[] alignNames = {"top_left", "top", "top_right", "left", "center", "right", "bottom_left", "bottom", "bottom_right"};
        for (int i = 0; i < 9; i++) {
            int col = i % 3, row = i / 3;
            final int index = i;
            Button b = tip(Button.builder(Component.empty(), btn -> setAlign(index))
                    .bounds(left + col * (H + 1), y + row * (H + 1), H, H)
                    .build(), t("align." + alignNames[i]));
            alignButtons[i] = panel(b);
        }
        int beside = left + 3 * (H + 1) + 4, besideWidth = PANEL - (beside - left);
        ratioButton = panel(tip(Button.builder(Component.empty(), b -> {
            keepRatio = !keepRatio;
            ratio = settings.width / settings.height;
            refresh();
        }).bounds(beside, y, besideWidth, H).build(), t("keep_ratio.tooltip")));
        panel(Button.builder(t("reset_size"), b -> {
            settings.width = 1;
            settings.height = 1;
            ratio = 1;
            changed();
        }).bounds(beside, y + H + 1, besideWidth, H).build());
        panel(tip(Button.builder(t("reset_angles"), b -> {
            settings.yaw = settings.pitch = settings.roll = 0;
            settings.offsetX = settings.offsetY = settings.offsetZ = 0;
            changed();
        }).bounds(beside, y + 2 * (H + 1), besideWidth, H).build(), t("reset_angles.tooltip")));
        y += 3 * (H + 1) + 3;

        width = slider(left, y, "width", 0.25, 8, 0.0625, MirrorSettings.MIN_SIZE, MirrorSettings.MAX_SIZE, v -> {
            settings.width = v;
            if (keepRatio) {
                settings.height = clampSize(v / ratio);
                height.set(settings.height);
            }
        });
        y += ROW;
        height = slider(left, y, "height", 0.25, 8, 0.0625, MirrorSettings.MIN_SIZE, MirrorSettings.MAX_SIZE, v -> {
            settings.height = v;
            if (keepRatio) {
                settings.width = clampSize(v * ratio);
                width.set(settings.width);
            }
        });
        y += ROW + 3;
        double o = MirrorSettings.MAX_OFFSET;
        offX = slider(left, y, "offset_x", -2, 2, 0.0625, -o, o, v -> settings.offsetX = v);
        y += ROW;
        offY = slider(left, y, "offset_y", -2, 2, 0.0625, -o, o, v -> settings.offsetY = v);
        y += ROW;
        offZ = slider(left, y, "offset_z", -1, 1, 0.0625, -o, o, v -> settings.offsetZ = v);
        y += ROW + 3;
        double a = MirrorSettings.MAX_YAW_PITCH;
        yaw = slider(left, y, "yaw", -a, a, 0.5, -a, a, v -> settings.yaw = v);
        y += ROW;
        pitch = slider(left, y, "pitch", -a, a, 0.5, -a, a, v -> settings.pitch = v);
        y += ROW;
        roll = slider(left, y, "roll", -MirrorSettings.MAX_ROLL, MirrorSettings.MAX_ROLL, 0.5,
                -MirrorSettings.MAX_ROLL, MirrorSettings.MAX_ROLL, v -> settings.roll = v);
        y += ROW;
        legacyNote = panel(new StringWidget(left, y + 2, PANEL, 10, t("legacy").withStyle(s -> s.withColor(TextColor.fromRgb(0xFFD37F))), font));
        legacyNote.setTooltip(Tooltip.create(t("legacy.tooltip")));

        // ---------------- Appearance (right) ----------------
        y = TOP;
        label(right, y, "appearance");
        y += ROW;
        // Like Signed Paintings: None -> Sign -> Block, plus a choice of fixed materials.
        frameButton = panel(tip(Button.builder(Component.empty(), b -> cycleFrameMode())
                .bounds(right, y, PANEL, H).build(), t("frame.tooltip")));
        y += ROW;
        materialPrev = panel(Button.builder(Component.literal("<"), b -> cycleMaterial(-1)).bounds(right, y, H, H).build());
        materialButton = panel(Button.builder(Component.empty(), b -> cycleMaterial(1))
                .bounds(right + H + 1, y, PANEL - 2 * (H + 1), H).build());
        materialNext = panel(Button.builder(Component.literal(">"), b -> cycleMaterial(1)).bounds(right + PANEL - H, y, H, H).build());
        y += ROW;
        frameWidth = slider(right, y, "frame_width", 0.5, MirrorSettings.MAX_FRAME_PX, 0.5, 0.5, MirrorSettings.MAX_FRAME_PX,
                v -> settings.frameWidth = v);
        y += ROW + 3;
        pixels = slider(right, y, "pixels", 0, 64, 1, 0, MirrorSettings.MAX_PIXELS_PER_BLOCK,
                v -> settings.pixelsPerBlock = (int) Math.round(v));
        pixels.slider.setTooltip(Tooltip.create(t("pixels.tooltip")));
        y += ROW + 3;

        panel(new StringWidget(right, y + 3, 40, 10, t("tint"), font));
        tintBox = panel(new EditBox(font, right + 42, y + 1, 52, H - 2, t("tint")));
        tintBox.setMaxLength(7);
        tintBox.setResponder(this::onTintTyped);
        tintBox.setTooltip(Tooltip.create(t("tint.tooltip")));
        swatch = panel(new StringWidget(right + 98, y + 3, PANEL - 98, 10, Component.empty(), font));
        y += ROW;
        opacity = slider(right, y, "opacity", 0, 100, 1, 0, 100, v -> settings.opacity = (int) Math.round(v));
        y += ROW;
        reflectivity = slider(right, y, "reflectivity", 0, 100, 1, 0, 100, v -> settings.reflectivity = (int) Math.round(v));
        reflectivity.slider.setTooltip(Tooltip.create(t("reflectivity.tooltip")));
        y += ROW + 3;
        signButton = panel(tip(Button.builder(Component.empty(), b -> {
            settings.hideSign = !settings.hideSign;
            changed();
        }).bounds(right, y, PANEL, H).build(), t("sign.tooltip")));
        y += ROW;
        panel(tip(Button.builder(t("remove"), b -> {
            settings = null;
            host.signmirrors$setLines(new String[] {"", "", "", ""});
            refresh();
        }).bounds(right, y, PANEL, H).build(), t("remove.tooltip")));
        y += ROW;
        warning = panel(new StringWidget(right, y + 2, PANEL, 10, t("too_long").withStyle(s -> s.withColor(TextColor.fromRgb(0xFF6060))), font));
        warning.setTooltip(Tooltip.create(t("too_long.tooltip")));

        // ---------------- Not a mirror yet ----------------
        makeButton = tip(Button.builder(t("make"), b -> {
            settings = new MirrorSettings();
            keepRatio = false;
            changed();
        }).bounds(screenWidth / 2 - 75, screenHeight / 4 + 120, 150, 20).build(), t("make.tooltip"));
        host.signmirrors$addWidget(makeButton);

        // Last, so it only gets clicks nothing else took: clicking empty space drops focus from a box.
        host.signmirrors$addWidget(new FocusCatcher(screenWidth, screenHeight));
    }

    private static <W extends AbstractWidget> W tip(W widget, Component tooltip) {
        widget.setTooltip(Tooltip.create(tooltip));
        return widget;
    }

    private <W extends AbstractWidget> W panel(W widget) {
        panelWidgets.add(widget);
        host.signmirrors$addWidget(widget);
        return widget;
    }

    private void label(int x, int y, String key) {
        panel(new StringWidget(x, y + 2, PANEL, 10, t(key).withStyle(s -> s.withBold(true)), font));
    }

    private InputSlider slider(int x, int y, String key, double sliderMin, double sliderMax, double step,
                               double min, double max, java.util.function.DoubleConsumer apply) {
        InputSlider s = new InputSlider(font, x, y, PANEL, H, t(key), sliderMin, sliderMax, step, min, max, 0);
        s.onChange(v -> {
            if (settings == null) return;
            apply.accept(v);
            changed();
        });
        panel(s.slider);
        panel(s.box);
        sliders.add(s);
        return s;
    }

    // =====================================================================================
    // State
    // =====================================================================================

    /** Re-reads the sign's lines (on open). */
    private void reload() {
        String[] l = host.signmirrors$lines();
        Optional<MirrorSettings> parsed = MirrorSettings.parse(l[0], l[1], l[2], l[3]);
        settings = parsed.orElse(null);
        if (settings != null) ratio = settings.width / settings.height;
        refresh();
    }

    /** Writes the settings into the sign and updates the controls. */
    private void changed() {
        if (settings == null) return;
        settings.sanitize();
        String[] lines = settings.toLines(host.signmirrors$maxLineWidth(), font::width);
        boolean fits = lines != null;
        if (fits) {
            settings.legacy = false;
            host.signmirrors$setLines(lines);
        }
        refresh();
        warning.visible = !fits;
    }

    /** Shows the right widgets for the current state and puts the current values in them. */
    private void refresh() {
        boolean mirror = settings != null;
        makeButton.visible = !mirror;
        for (AbstractWidget w : panelWidgets) w.visible = mirror;
        warning.visible = false;
        if (!mirror) return;

        MirrorSettings s = settings;
        int selected = (2 - s.vAlign.ordinal()) * 3 + s.hAlign.ordinal(); // rows top..bottom, cols left..right
        for (int i = 0; i < 9; i++) {
            alignButtons[i].setMessage(Component.literal(i == selected ? "■" : ""));
            alignButtons[i].active = i != selected;
        }
        ratioButton.setMessage(t(keepRatio ? "keep_ratio.on" : "keep_ratio.off"));
        width.set(s.width);
        height.set(s.height);
        offX.set(s.offsetX);
        offY.set(s.offsetY);
        offZ.set(s.offsetZ);
        yaw.set(s.yaw);
        pitch.set(s.pitch);
        roll.set(s.roll);
        if (s.frame.isMaterial()) lastMaterial = s.frame;
        String[] modes = {"none", "sign_wood", "block", "material"};
        frameButton.setMessage(t("frame." + modes[s.frame.mode()]));
        materialButton.setMessage(t("material." + lastMaterial.key));
        boolean material = s.frame.isMaterial();
        materialPrev.visible = materialButton.visible = materialNext.visible = material;
        frameWidth.set(s.frameWidth);
        frameWidth.setActive(s.frame != MirrorSettings.Frame.NONE);
        pixels.set(s.pixelsPerBlock);
        opacity.set(s.opacity);
        reflectivity.set(s.reflectivity);
        String hex = String.format(Locale.ROOT, "%06X", s.tint);
        if (!tintBox.isFocused() && !tintBox.getValue().equalsIgnoreCase(hex) && !tintBox.getValue().equalsIgnoreCase("#" + hex)) {
            tintBox.setValue(hex);
        }
        swatch.setMessage(Component.literal("■■■").withStyle(st -> st.withColor(TextColor.fromRgb(s.tint))));
        signButton.setMessage(t(s.hideSign ? "sign.hidden" : "sign.shown"));
        legacyNote.visible = s.legacy;
    }

    private void setAlign(int index) {
        settings.hAlign = MirrorSpec.HAlign.values()[index % 3];
        settings.vAlign = MirrorSpec.VAlign.values()[2 - index / 3];
        changed();
    }

    private void cycleFrameMode() {
        settings.frame = switch (settings.frame.mode()) {
            case 0 -> MirrorSettings.Frame.SIGN_WOOD;
            case 1 -> MirrorSettings.Frame.BLOCK;
            case 2 -> lastMaterial;
            default -> MirrorSettings.Frame.NONE;
        };
        changed();
    }

    private void cycleMaterial(int step) {
        settings.frame = lastMaterial.nextMaterial(step);
        changed();
    }

    private void onTintTyped(String text) {
        if (settings == null) return;
        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6) return;
        try {
            int rgb = Integer.parseInt(hex, 16);
            if (rgb != settings.tint) {
                settings.tint = rgb;
                changed();
            }
        } catch (NumberFormatException ignored) {
            // not hex yet
        }
    }

    private static double clampSize(double v) {
        return Math.max(MirrorSettings.MIN_SIZE, Math.min(MirrorSettings.MAX_SIZE, v));
    }

    // =====================================================================================
    // Input routing (the sign editor would otherwise type every key into the sign)
    // =====================================================================================

    /** True if a control of ours has focus (the sign's text cursor should not show). */
    public boolean hasFocus() {
        if (settings == null) return false;
        if (tintBox.isFocused()) return true;
        for (InputSlider s : sliders) if (s.isFocused()) return true;
        return false;
    }

    public boolean keyPressed(KeyEvent event) {
        if (!hasFocus() || event.input() == KEY_ESCAPE) return false;
        if (tintBox.isFocused()) {
            if (event.input() == 257) tintBox.setFocused(false); else tintBox.keyPressed(event);
            return true;
        }
        for (InputSlider s : sliders) if (s.isFocused()) return s.keyPressed(event);
        return false;
    }

    public boolean charTyped(CharacterEvent event) {
        if (!hasFocus()) return false;
        if (tintBox.isFocused()) {
            tintBox.charTyped(event);
            return true;
        }
        for (InputSlider s : sliders) if (s.isFocused()) return s.charTyped(event);
        return false;
    }

    private static net.minecraft.network.chat.MutableComponent t(String key) {
        return Component.translatable("signmirrors.editor." + key);
    }

    /** Invisible full-screen widget that takes clicks on empty space while one of our boxes has focus. */
    private final class FocusCatcher extends AbstractWidget {
        FocusCatcher(int width, int height) {
            super(0, 0, width, height, Component.empty());
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {}

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {}

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            return hasFocus(); // taking the click moves focus here, off the box
        }

        @Override
        public void playDownSound(SoundManager soundManager) {}
    }
}
