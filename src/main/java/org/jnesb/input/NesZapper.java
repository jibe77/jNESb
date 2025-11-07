package org.jnesb.input;

import java.util.Objects;
import java.util.function.IntBinaryOperator;

import org.jnesb.ppu.Ppu2C02;

/**
 * Minimal emulation of the NES Zapper light gun.
 * Reports trigger state via bit 4 and light sensor state via bit 3.
 */
public final class NesZapper {

    private static final double LIGHT_THRESHOLD = 180.0;

    private final IntBinaryOperator pixelSampler;
    private int targetX = Ppu2C02.SCREEN_WIDTH / 2;
    private int targetY = Ppu2C02.SCREEN_HEIGHT / 2;
    private boolean triggerPressed;
    private boolean aimWithinScreen;

    public NesZapper(IntBinaryOperator pixelSampler) {
        this.pixelSampler = Objects.requireNonNull(pixelSampler, "pixelSampler");
    }

    public static NesZapper attachedTo(Ppu2C02 ppu) {
        Objects.requireNonNull(ppu, "ppu");
        return new NesZapper(ppu::sampleScreenPixel);
    }

    public void reset() {
        triggerPressed = false;
        aimAt(Ppu2C02.SCREEN_WIDTH / 2, Ppu2C02.SCREEN_HEIGHT / 2);
    }

    public void aimAt(int x, int y) {
        aimWithinScreen = x >= 0 && x < Ppu2C02.SCREEN_WIDTH
                && y >= 0 && y < Ppu2C02.SCREEN_HEIGHT;
        targetX = clamp(x, 0, Ppu2C02.SCREEN_WIDTH - 1);
        targetY = clamp(y, 0, Ppu2C02.SCREEN_HEIGHT - 1);
    }

    public void setTriggerPressed(boolean pressed) {
        triggerPressed = pressed;
    }

    public int read() {
        int data = 0;
        if (!detectLight()) {
            data |= 0x08;
        }
        if (!triggerPressed) {
            data |= 0x10;
        }
        return data;
    }

    private boolean detectLight() {
        if (!aimWithinScreen) {
            return false;
        }
        int rgb = pixelSampler.applyAsInt(targetX, targetY);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        double luminance = 0.299 * r + 0.587 * g + 0.114 * b;
        return luminance >= LIGHT_THRESHOLD;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
