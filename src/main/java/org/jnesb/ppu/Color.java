package org.jnesb.ppu;

/**
 * Represents an RGB color with components clamped to 0-255.
 */
record Color(int r, int g, int b) {
    /**
     * Compact constructor that clamps all color components to valid range.
     */
    Color {
        r = clamp(r);
        g = clamp(g);
        b = clamp(b);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
