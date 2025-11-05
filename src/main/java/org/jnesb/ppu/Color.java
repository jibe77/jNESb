package org.jnesb.ppu;

final class Color {
    final int r;
    final int g;
    final int b;

    Color(int r, int g, int b) {
        this.r = clamp(r);
        this.g = clamp(g);
        this.b = clamp(b);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
