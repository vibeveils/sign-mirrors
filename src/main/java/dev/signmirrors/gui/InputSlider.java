package dev.signmirrors.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * A labelled slider with a number box beside it. The slider covers the common range; the box
 * accepts anything in the full range (e.g. offsets beyond what fits on the slider).
 */
final class InputSlider {
    private static final int KEY_ENTER = 257, KEY_RIGHT = 262, KEY_LEFT = 263;

    final Slider slider;
    final EditBox box;
    private final double min, max, sliderMin, sliderMax, step;
    private DoubleConsumer onChange = v -> {};
    private double value;
    private boolean updating;

    InputSlider(Font font, int x, int y, int width, int height, Component label,
                double sliderMin, double sliderMax, double step, double min, double max, double value) {
        this.min = min;
        this.max = max;
        this.sliderMin = sliderMin;
        this.sliderMax = sliderMax;
        this.step = step;
        int boxWidth = 34;
        this.slider = new Slider(x, y, width - boxWidth - 2, height, label);
        this.box = new EditBox(font, x + width - boxWidth + 1, y + 1, boxWidth - 2, height - 2, label);
        this.box.setMaxLength(8);
        this.box.setResponder(this::onTyped);
        set(value);
    }

    void onChange(DoubleConsumer onChange) {
        this.onChange = onChange;
    }

    double value() {
        return value;
    }

    /** Sets the shown value without notifying (leaving the box alone while it's being typed in). */
    void set(double v) {
        value = clamp(v, min, max);
        updating = true;
        try {
            if (!box.isFocused()) {
                box.setValue(format(value));
                box.moveCursorToStart(false);
            }
            slider.show(value);
        } finally {
            updating = false;
        }
    }

    void setVisible(boolean visible) {
        slider.visible = visible;
        box.visible = visible;
    }

    void setActive(boolean active) {
        slider.active = active;
        box.active = active;
        box.setEditable(active);
    }

    boolean isFocused() {
        return slider.isFocused() || box.isFocused();
    }

    boolean keyPressed(KeyEvent event) {
        if (box.isFocused()) {
            if (event.input() == KEY_ENTER) {
                box.setFocused(false);
                return true;
            }
            box.keyPressed(event);
            return true; // never let typing in the box reach the sign
        }
        if (slider.isFocused()) {
            if (event.input() == KEY_LEFT || event.input() == KEY_RIGHT) {
                changeTo(value + (event.input() == KEY_LEFT ? -step : step));
                return true;
            }
            return slider.keyPressed(event);
        }
        return false;
    }

    boolean charTyped(CharacterEvent event) {
        if (box.isFocused()) {
            box.charTyped(event);
            return true;
        }
        return slider.isFocused();
    }

    private void onTyped(String text) {
        if (updating) return;
        try {
            double v = Double.parseDouble(text.trim().replace(',', '.'));
            if (!Double.isFinite(v)) return;
            value = clamp(v, min, max);
            slider.show(value);
            onChange.accept(value);
        } catch (NumberFormatException ignored) {
            // half-typed ("-", "1.") -- wait for more
        }
    }

    private void changeTo(double v) {
        set(snap(v));
        onChange.accept(value);
    }

    private double snap(double v) {
        return step > 0 ? Math.round(v / step) * step : v;
    }

    static String format(double v) {
        String s = BigDecimal.valueOf(Math.round(v * 10000) / 10000.0).stripTrailingZeros().toPlainString();
        return s.equals("-0") ? "0" : s.toLowerCase(Locale.ROOT);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    final class Slider extends AbstractSliderButton {
        Slider(int x, int y, int width, int height, Component label) {
            super(x, y, width, height, label, 0);
        }

        void show(double v) {
            this.value = clamp((v - sliderMin) / (sliderMax - sliderMin), 0, 1);
        }

        @Override
        protected void updateMessage() {
            // The label stays fixed; the number is in the box.
        }

        @Override
        protected void applyValue() {
            double v = snap(sliderMin + (sliderMax - sliderMin) * this.value);
            InputSlider.this.value = clamp(v, min, max);
            updating = true;
            try {
                box.setValue(format(InputSlider.this.value));
                box.moveCursorToStart(false);
            } finally {
                updating = false;
            }
            onChange.accept(InputSlider.this.value);
        }
    }
}
