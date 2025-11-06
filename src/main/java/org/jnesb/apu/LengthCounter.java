package org.jnesb.apu;

final class LengthCounter {

    private static final int[] TABLE = {
            10, 254, 20, 2, 40, 4, 80, 6,
            160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22,
            192, 24, 72, 26, 16, 28, 32, 30
    };

    private int value;
    private boolean halt;

    void setHalt(boolean halt) {
        this.halt = halt;
    }

    void load(int index) {
        int idx = index & 0x1F;
        if (idx < TABLE.length) {
            value = TABLE[idx];
        }
    }

    void clear() {
        value = 0;
    }

    void clock(boolean enabled) {
        if (!enabled) {
            value = 0;
            return;
        }
        if (!halt && value > 0) {
            value--;
        }
    }

    boolean isActive() {
        return value > 0;
    }

    int value() {
        return value;
    }
}
