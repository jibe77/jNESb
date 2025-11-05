package org.jnesb.ppu;

import java.util.concurrent.ThreadLocalRandom;

import org.jnesb.cartridge.Cartridge;

/**
 * Minimal Java port of the OneLoneCoder olc2C02 PPU stub (OLC-3 license).
 * Implemented to satisfy bus/cartridge integration introduced in Part 3 of the
 * original tutorial series. Rendering is represented by an in-memory sprite
 * buffer.
 */
public final class Ppu2C02 {

    private final int[][] nameTable = new int[2][1024];
    private final int[][] patternTable = new int[2][4096];
    private final int[] paletteTable = new int[32];

    private final Color[] paletteScreen = new Color[0x40];
    private final Sprite screenSprite = new Sprite(256, 240);
    private final Sprite[] nameTableSprites = {new Sprite(256, 240), new Sprite(256, 240)};
    private final Sprite[] patternTableSprites = {new Sprite(128, 128), new Sprite(128, 128)};

    private Cartridge cartridge;

    private int scanline = 0;
    private int cycle = 0;
    private volatile boolean frameComplete;

    public Ppu2C02() {
        initializePalette();
    }

    public void connectCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    public Sprite getScreen() {
        return screenSprite;
    }

    public Sprite getNameTable(int index) {
        return nameTableSprites[index & 1];
    }

    public Sprite getPatternTable(int index) {
        return patternTableSprites[index & 1];
    }

    public boolean isFrameComplete() {
        return frameComplete;
    }

    public void clearFrameFlag() {
        frameComplete = false;
    }

    public int cpuRead(int address, boolean readOnly) {
        address &= 0x0007;
        // Part 3 stub: registers not yet implemented
        return 0x00;
    }

    public void cpuWrite(int address, int data) {
        address &= 0x0007;
        data &= 0xFF;
        // Part 3 stub: registers not yet implemented
    }

    public int ppuRead(int address, boolean readOnly) {
        address &= 0x3FFF;
        if (cartridge != null) {
            int[] data = new int[1];
            if (cartridge.ppuRead(address, data)) {
                return data[0];
            }
        }
        return 0x00;
    }

    public void ppuWrite(int address, int data) {
        address &= 0x3FFF;
        data &= 0xFF;
        if (cartridge != null) {
            cartridge.ppuWrite(address, data);
        }
    }

    public void clock() {
        // Basic placeholder rendering: random coloured noise
        if (cycle >= 1 && cycle <= 256 && scanline >= 0 && scanline < 240) {
            boolean bright = ThreadLocalRandom.current().nextBoolean();
            screenSprite.setPixel(cycle - 1, scanline, paletteScreen[bright ? 0x3F : 0x30]);
        }

        cycle++;
        if (cycle >= 341) {
            cycle = 0;
            scanline++;
            if (scanline >= 261) {
                scanline = -1;
                frameComplete = true;
            }
        }
    }

    private void initializePalette() {
        // Direct translation of the palette table from the original C++ source
        paletteScreen[0x00] = new Color(84, 84, 84);
        paletteScreen[0x01] = new Color(0, 30, 116);
        paletteScreen[0x02] = new Color(8, 16, 144);
        paletteScreen[0x03] = new Color(48, 0, 136);
        paletteScreen[0x04] = new Color(68, 0, 100);
        paletteScreen[0x05] = new Color(92, 0, 48);
        paletteScreen[0x06] = new Color(84, 4, 0);
        paletteScreen[0x07] = new Color(60, 24, 0);
        paletteScreen[0x08] = new Color(32, 42, 0);
        paletteScreen[0x09] = new Color(8, 58, 0);
        paletteScreen[0x0A] = new Color(0, 64, 0);
        paletteScreen[0x0B] = new Color(0, 60, 0);
        paletteScreen[0x0C] = new Color(0, 50, 60);
        paletteScreen[0x0D] = new Color(0, 0, 0);
        paletteScreen[0x0E] = new Color(0, 0, 0);
        paletteScreen[0x0F] = new Color(0, 0, 0);

        paletteScreen[0x10] = new Color(152, 150, 152);
        paletteScreen[0x11] = new Color(8, 76, 196);
        paletteScreen[0x12] = new Color(48, 50, 236);
        paletteScreen[0x13] = new Color(92, 30, 228);
        paletteScreen[0x14] = new Color(136, 20, 176);
        paletteScreen[0x15] = new Color(160, 20, 100);
        paletteScreen[0x16] = new Color(152, 34, 32);
        paletteScreen[0x17] = new Color(120, 60, 0);
        paletteScreen[0x18] = new Color(84, 90, 0);
        paletteScreen[0x19] = new Color(40, 114, 0);
        paletteScreen[0x1A] = new Color(8, 124, 0);
        paletteScreen[0x1B] = new Color(0, 118, 40);
        paletteScreen[0x1C] = new Color(0, 102, 120);
        paletteScreen[0x1D] = new Color(0, 0, 0);
        paletteScreen[0x1E] = new Color(0, 0, 0);
        paletteScreen[0x1F] = new Color(0, 0, 0);

        paletteScreen[0x20] = new Color(236, 238, 236);
        paletteScreen[0x21] = new Color(76, 154, 236);
        paletteScreen[0x22] = new Color(120, 124, 236);
        paletteScreen[0x23] = new Color(176, 98, 236);
        paletteScreen[0x24] = new Color(228, 84, 236);
        paletteScreen[0x25] = new Color(236, 88, 180);
        paletteScreen[0x26] = new Color(236, 106, 100);
        paletteScreen[0x27] = new Color(212, 136, 32);
        paletteScreen[0x28] = new Color(160, 170, 0);
        paletteScreen[0x29] = new Color(116, 196, 0);
        paletteScreen[0x2A] = new Color(76, 208, 32);
        paletteScreen[0x2B] = new Color(56, 204, 108);
        paletteScreen[0x2C] = new Color(56, 180, 204);
        paletteScreen[0x2D] = new Color(60, 60, 60);
        paletteScreen[0x2E] = new Color(0, 0, 0);
        paletteScreen[0x2F] = new Color(0, 0, 0);

        paletteScreen[0x30] = new Color(236, 238, 236);
        paletteScreen[0x31] = new Color(168, 204, 236);
        paletteScreen[0x32] = new Color(188, 188, 236);
        paletteScreen[0x33] = new Color(212, 178, 236);
        paletteScreen[0x34] = new Color(236, 174, 236);
        paletteScreen[0x35] = new Color(236, 174, 212);
        paletteScreen[0x36] = new Color(236, 180, 176);
        paletteScreen[0x37] = new Color(228, 196, 144);
        paletteScreen[0x38] = new Color(204, 210, 120);
        paletteScreen[0x39] = new Color(180, 222, 120);
        paletteScreen[0x3A] = new Color(168, 226, 144);
        paletteScreen[0x3B] = new Color(152, 226, 180);
        paletteScreen[0x3C] = new Color(160, 214, 228);
        paletteScreen[0x3D] = new Color(160, 162, 160);
        paletteScreen[0x3E] = new Color(0, 0, 0);
        paletteScreen[0x3F] = new Color(0, 0, 0);
    }
}
