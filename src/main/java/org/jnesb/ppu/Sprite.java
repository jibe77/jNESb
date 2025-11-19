package org.jnesb.ppu;

import java.util.Arrays;

final class Sprite {

    private final int width;
    private final int height;
    private final int[] pixels;

    Sprite(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    void setPixel(int x, int y, Color color) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return;
        }
        pixels[y * width + x] = (color.r() << 16) | (color.g() << 8) | color.b();
    }

    int getPixel(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return 0;
        }
        return pixels[y * width + x];
    }

    void clear() {
        Arrays.fill(pixels, 0);
    }

    int[] data() {
        return pixels;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }
}
