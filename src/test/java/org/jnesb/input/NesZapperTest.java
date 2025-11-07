package org.jnesb.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.IntBinaryOperator;

import org.junit.jupiter.api.Test;

final class NesZapperTest {

    @Test
    void readSetsBitsWhenDarkAndReleased() {
        TestSampler sampler = new TestSampler();
        sampler.setRgb(0x000000);
        NesZapper zapper = new NesZapper(sampler);
        zapper.aimAt(50, 50);

        int data = zapper.read();

        assertEquals(0x18, data & 0x18);
    }

    @Test
    void readClearsLightBitWhenPixelBright() {
        TestSampler sampler = new TestSampler();
        sampler.setRgb(0xFFFFFF);
        NesZapper zapper = new NesZapper(sampler);
        zapper.aimAt(120, 100);
        zapper.setTriggerPressed(true);

        int data = zapper.read();

        assertEquals(0x00, data & 0x18);
    }

    @Test
    void aimOutsideScreenDisablesLightDetection() {
        TestSampler sampler = new TestSampler();
        sampler.setRgb(0xFFFFFF);
        NesZapper zapper = new NesZapper(sampler);
        zapper.aimAt(-10, -10);
        zapper.setTriggerPressed(true);

        int data = zapper.read();

        assertEquals(0x08, data & 0x18);
    }

    private static final class TestSampler implements IntBinaryOperator {
        private int rgb;

        void setRgb(int rgb) {
            this.rgb = rgb;
        }

        @Override
        public int applyAsInt(int x, int y) {
            return rgb;
        }
    }
}
