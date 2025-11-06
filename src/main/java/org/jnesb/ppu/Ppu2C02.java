package org.jnesb.ppu;

import org.jnesb.cartridge.Cartridge;
import org.jnesb.cartridge.Cartridge.Mirror;

/**
 * Java port of the OneLoneCoder olc2C02 PPU background pipeline (Part 4).
 * Implements nametable, palette and background pixel generation logic.
 */
public final class Ppu2C02 {

    private static final int CONTROL_INCREMENT_MODE = 0x04;
    private static final int CONTROL_PATTERN_BACKGROUND = 0x10;
    private static final int CONTROL_ENABLE_NMI = 0x80;

    private static final int MASK_RENDER_BACKGROUND_LEFT = 0x02;
    private static final int MASK_RENDER_BACKGROUND = 0x08;

    private static final int STATUS_SPRITE_OVERFLOW = 0x20;
    private static final int STATUS_SPRITE_ZERO_HIT = 0x40;
    private static final int STATUS_VERTICAL_BLANK = 0x80;

    private final int[][] nameTable = new int[2][1024];
    private final int[][] patternTable = new int[2][4096];
    private final int[] paletteTable = new int[32];

    private final Color[] paletteScreen = new Color[0x40];
    private final Sprite screenSprite = new Sprite(256, 240);
    private final Sprite[] nameTableSprites = {new Sprite(256, 240), new Sprite(256, 240)};
    private final Sprite[] patternTableSprites = {new Sprite(128, 128), new Sprite(128, 128)};

    private Cartridge cartridge;
    private Mirror mirrorMode = Mirror.HORIZONTAL;

    private int scanline = 0;
    private int cycle = 0;
    private boolean frameComplete;
    private boolean nmiRequested;

    private int control;
    private int mask;
    private int status;
    private int oamAddress;
    private final int[] oam = new int[256];
    private int addressLatch;
    private int ppuDataBuffer;
    private int fineX;

    private final LoopyRegister vramAddress = new LoopyRegister();
    private final LoopyRegister tramAddress = new LoopyRegister();

    private int bgShifterPatternLow;
    private int bgShifterPatternHigh;
    private int bgShifterAttributeLow;
    private int bgShifterAttributeHigh;
    private int bgNextTileId;
    private int bgNextTileAttribute;
    private int bgNextTileLsb;
    private int bgNextTileMsb;

    public Ppu2C02() {
        initializePalette();
    }

    public void reset() {
        fineX = 0;
        addressLatch = 0;
        ppuDataBuffer = 0;
        scanline = 0;
        cycle = 0;
        control = 0;
        mask = 0;
        status = 0;
        oamAddress = 0;
        bgNextTileId = 0;
        bgNextTileAttribute = 0;
        bgNextTileLsb = 0;
        bgNextTileMsb = 0;
        bgShifterPatternLow = 0;
        bgShifterPatternHigh = 0;
        bgShifterAttributeLow = 0;
        bgShifterAttributeHigh = 0;
        vramAddress.set(0);
        tramAddress.set(0);
        frameComplete = false;
        nmiRequested = false;
    }

    public void connectCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
        if (cartridge != null) {
            this.mirrorMode = cartridge.mirror();
        }
    }

    public Sprite getScreen() {
        return screenSprite;
    }

    public Sprite getNameTable(int index) {
        Sprite sprite = nameTableSprites[index & 1];
        int base = 0x2000 + ((index & 1) * 0x0400);
        int patternBase = (control & CONTROL_PATTERN_BACKGROUND) != 0 ? 0x1000 : 0x0000;

        for (int tileY = 0; tileY < 30; tileY++) {
            for (int tileX = 0; tileX < 32; tileX++) {
                int tileIndex = tileY * 32 + tileX;
                int tileId = ppuRead(base + tileIndex, true);
                int attributeAddress = base + 0x03C0 + (tileY / 4) * 8 + (tileX / 4);
                int attribute = ppuRead(attributeAddress, true);
                int shift = ((tileY & 0x02) << 1) | (tileX & 0x02);
                int palette = (attribute >> shift) & 0x03;

                int tileAddress = patternBase + (tileId << 4);
                for (int row = 0; row < 8; row++) {
                    int lsb = ppuRead(tileAddress + row, true);
                    int msb = ppuRead(tileAddress + row + 8, true);
                    for (int col = 0; col < 8; col++) {
                        int bit = 7 - col;
                        int pixel = ((lsb >> bit) & 0x01) | (((msb >> bit) & 0x01) << 1);
                        sprite.setPixel(tileX * 8 + col, tileY * 8 + row,
                                getColorFromPaletteRam(palette, pixel));
                    }
                }
            }
        }
        return sprite;
    }

    public Sprite getPatternTable(int index) {
        return getPatternTable(index, 0);
    }

    public Sprite getPatternTable(int index, int palette) {
        Sprite sprite = patternTableSprites[index & 1];
        int patternBase = (index & 1) * 0x1000;

        for (int tileY = 0; tileY < 16; tileY++) {
            for (int tileX = 0; tileX < 16; tileX++) {
                int tileOffset = tileY * 256 + tileX * 16;
                for (int row = 0; row < 8; row++) {
                    int lsb = ppuRead(patternBase + tileOffset + row, true);
                    int msb = ppuRead(patternBase + tileOffset + row + 8, true);
                    for (int col = 0; col < 8; col++) {
                        int bit = 7 - col;
                        int pixel = ((lsb >> bit) & 0x01) | (((msb >> bit) & 0x01) << 1);
                        sprite.setPixel(tileX * 8 + col, tileY * 8 + row,
                                getColorFromPaletteRam(palette, pixel));
                    }
                }
            }
        }
        return sprite;
    }

    public boolean isFrameComplete() {
        return frameComplete;
    }

    public void clearFrameFlag() {
        frameComplete = false;
    }

    public boolean pollNmi() {
        if (nmiRequested) {
            nmiRequested = false;
            return true;
        }
        return false;
    }

    public int cpuRead(int address, boolean readOnly) {
        address &= 0x0007;
        int data = 0x00;

        switch (address) {
            case 0x0000 -> data = control;
            case 0x0001 -> data = mask;
            case 0x0002 -> {
                data = (status & 0xE0) | (ppuDataBuffer & 0x1F);
                status &= ~STATUS_VERTICAL_BLANK;
                addressLatch = 0;
            }
            case 0x0004 -> data = oam[oamAddress & 0xFF] & 0xFF;
            case 0x0007 -> {
                data = ppuDataBuffer;
                ppuDataBuffer = ppuRead(vramAddress.get(), true);
                if ((vramAddress.get() & 0x3FFF) >= 0x3F00) {
                    data = ppuDataBuffer;
                }
                vramAddress.set((vramAddress.get() + getVRamIncrement()) & 0x7FFF);
            }
            default -> {
            }
        }

        return data & 0xFF;
    }

    public void cpuWrite(int address, int data) {
        address &= 0x0007;
        data &= 0xFF;

        switch (address) {
            case 0x0000 -> {
                control = data;
                tramAddress.setNametableX(data & 0x01);
                tramAddress.setNametableY((data >> 1) & 0x01);
            }
            case 0x0001 -> mask = data;
            case 0x0002 -> status = data;
            case 0x0003 -> oamAddress = data;
            case 0x0004 -> {
                oam[oamAddress & 0xFF] = data;
                oamAddress = (oamAddress + 1) & 0xFF;
            }
            case 0x0005 -> {
                if (addressLatch == 0) {
                    fineX = data & 0x07;
                    tramAddress.setCoarseX((data >> 3) & 0x1F);
                    addressLatch = 1;
                } else {
                    tramAddress.setFineY(data & 0x07);
                    tramAddress.setCoarseY((data >> 3) & 0x1F);
                    addressLatch = 0;
                }
            }
            case 0x0006 -> {
                if (addressLatch == 0) {
                    tramAddress.set(((data & 0x3F) << 8) | (tramAddress.get() & 0x00FF));
                    addressLatch = 1;
                } else {
                    tramAddress.set((tramAddress.get() & 0xFF00) | data);
                    vramAddress.set(tramAddress.get());
                    addressLatch = 0;
                }
            }
            case 0x0007 -> {
                ppuWrite(vramAddress.get(), data);
                vramAddress.set((vramAddress.get() + getVRamIncrement()) & 0x7FFF);
            }
            default -> {
            }
        }
    }

    public int ppuRead(int address, boolean readOnly) {
        address &= 0x3FFF;
        int data = 0x00;

        if (cartridge != null) {
            int[] cartridgeData = new int[1];
            if (cartridge.ppuRead(address, cartridgeData)) {
                return cartridgeData[0] & 0xFF;
            }
        }

        if (address >= 0x0000 && address <= 0x1FFF) {
            data = patternTable[(address >> 12) & 0x01][address & 0x0FFF];
        } else if (address >= 0x2000 && address <= 0x3EFF) {
            int resolved = address & 0x0FFF;
            int table = resolveNameTable(resolved);
            int offset = resolved & 0x03FF;
            data = nameTable[table][offset];
        } else if (address >= 0x3F00 && address <= 0x3FFF) {
            int paletteAddress = address & 0x001F;
            paletteAddress = remapPaletteAddress(paletteAddress);
            data = paletteTable[paletteAddress] & 0xFF;
        }

        return data & 0xFF;
    }

    public void ppuWrite(int address, int data) {
        address &= 0x3FFF;
        data &= 0xFF;

        if (cartridge != null && cartridge.ppuWrite(address, data)) {
            return;
        }

        if (address >= 0x0000 && address <= 0x1FFF) {
            patternTable[(address >> 12) & 0x01][address & 0x0FFF] = data;
        } else if (address >= 0x2000 && address <= 0x3EFF) {
            int resolved = address & 0x0FFF;
            int table = resolveNameTable(resolved);
            int offset = resolved & 0x03FF;
            nameTable[table][offset] = data;
        } else if (address >= 0x3F00 && address <= 0x3FFF) {
            int paletteAddress = remapPaletteAddress(address & 0x001F);
            paletteTable[paletteAddress] = data & 0x3F;
        }
    }

    public void clock() {
        boolean preRenderLine = scanline == -1;
        boolean visibleScanline = scanline >= 0 && scanline < 240;
        boolean visibleCycle = cycle >= 1 && cycle <= 256;

        if (preRenderLine && cycle == 1) {
            status &= ~(STATUS_VERTICAL_BLANK | STATUS_SPRITE_ZERO_HIT | STATUS_SPRITE_OVERFLOW);
            nmiRequested = false;
        }

        if ((visibleScanline || preRenderLine) && cycle > 0 && cycle < 341) {
            if ((cycle >= 2 && cycle <= 257) || (cycle >= 321 && cycle <= 337)) {
                updateBackgroundShifters();

                switch ((cycle - 1) & 0x07) {
                    case 0 -> {
                        loadBackgroundShifters();
                        bgNextTileId = ppuRead(0x2000 | (vramAddress.get() & 0x0FFF), true);
                    }
                    case 2 -> {
                        int attributeAddress = 0x23C0
                                | (vramAddress.get() & 0x0C00)
                                | ((vramAddress.get() >> 4) & 0x38)
                                | ((vramAddress.get() >> 2) & 0x07);
                        int attribute = ppuRead(attributeAddress, true);
                        int shift = ((vramAddress.coarseY() & 0x02) << 1) | (vramAddress.coarseX() & 0x02);
                        bgNextTileAttribute = (attribute >> shift) & 0x03;
                    }
                    case 4 -> {
                        int fineY = vramAddress.fineY();
                        int base = ((control & CONTROL_PATTERN_BACKGROUND) != 0 ? 0x1000 : 0x0000);
                        int address = base + (bgNextTileId << 4) + fineY;
                        bgNextTileLsb = ppuRead(address, true);
                    }
                    case 6 -> {
                        int fineY = vramAddress.fineY();
                        int base = ((control & CONTROL_PATTERN_BACKGROUND) != 0 ? 0x1000 : 0x0000);
                        int address = base + (bgNextTileId << 4) + fineY + 8;
                        bgNextTileMsb = ppuRead(address, true);
                    }
                    case 7 -> incrementScrollX();
                    default -> {
                    }
                }
            }

            if (cycle == 256) {
                incrementScrollY();
            } else if (cycle == 257) {
                loadBackgroundShifters();
                transferAddressX();
            } else if (cycle == 338 || cycle == 340) {
                bgNextTileId = ppuRead(0x2000 | (vramAddress.get() & 0x0FFF), true);
            }

            if (preRenderLine && cycle >= 280 && cycle < 305) {
                transferAddressY();
            }
        }

        int bgPixel = 0;
        int bgPalette = 0;

        if (isBackgroundRenderingEnabled()) {
            int bitMask = 0x8000 >>> fineX;

            int p0 = (bgShifterPatternLow & bitMask) != 0 ? 1 : 0;
            int p1 = (bgShifterPatternHigh & bitMask) != 0 ? 1 : 0;
            bgPixel = (p1 << 1) | p0;

            int pal0 = (bgShifterAttributeLow & bitMask) != 0 ? 1 : 0;
            int pal1 = (bgShifterAttributeHigh & bitMask) != 0 ? 1 : 0;
            bgPalette = (pal1 << 1) | pal0;

            if (!isBackgroundLeftEnabled() && cycle <= 8) {
                bgPixel = 0;
                bgPalette = 0;
            }
        }

        int pixel = 0;
        int palette = 0;

        if (bgPixel != 0) {
            pixel = bgPixel;
            palette = bgPalette;
        }

        if (visibleScanline && visibleCycle) {
            screenSprite.setPixel(cycle - 1, scanline, getColorFromPaletteRam(palette, pixel));
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

        if (scanline == 241 && cycle == 1) {
            status |= STATUS_VERTICAL_BLANK;
            if ((control & CONTROL_ENABLE_NMI) != 0) {
                nmiRequested = true;
            }
        }
    }

    private int getVRamIncrement() {
        return (control & CONTROL_INCREMENT_MODE) != 0 ? 32 : 1;
    }

    private boolean isBackgroundRenderingEnabled() {
        return (mask & MASK_RENDER_BACKGROUND) != 0;
    }

    private boolean isBackgroundLeftEnabled() {
        return (mask & MASK_RENDER_BACKGROUND_LEFT) != 0;
    }

    private void updateBackgroundShifters() {
        if (!isBackgroundRenderingEnabled()) {
            return;
        }

        bgShifterPatternLow = ((bgShifterPatternLow << 1) & 0xFFFF);
        bgShifterPatternHigh = ((bgShifterPatternHigh << 1) & 0xFFFF);
        bgShifterAttributeLow = ((bgShifterAttributeLow << 1) & 0xFFFF);
        bgShifterAttributeHigh = ((bgShifterAttributeHigh << 1) & 0xFFFF);
    }

    private void loadBackgroundShifters() {
        if (!isBackgroundRenderingEnabled()) {
            return;
        }

        bgShifterPatternLow = (bgShifterPatternLow & 0xFF00) | (bgNextTileLsb & 0x00FF);
        bgShifterPatternHigh = (bgShifterPatternHigh & 0xFF00) | (bgNextTileMsb & 0x00FF);

        int attrib = bgNextTileAttribute & 0x03;
        int attribLow = (attrib & 0x01) != 0 ? 0xFF : 0x00;
        int attribHigh = (attrib & 0x02) != 0 ? 0xFF : 0x00;

        bgShifterAttributeLow = (bgShifterAttributeLow & 0xFF00) | attribLow;
        bgShifterAttributeHigh = (bgShifterAttributeHigh & 0xFF00) | attribHigh;
    }

    private void incrementScrollX() {
        if (!isBackgroundRenderingEnabled()) {
            return;
        }

        if (vramAddress.coarseX() == 31) {
            vramAddress.setCoarseX(0);
            vramAddress.setNametableX(vramAddress.nametableX() ^ 0x01);
        } else {
            vramAddress.setCoarseX((vramAddress.coarseX() + 1) & 0x1F);
        }
    }

    private void incrementScrollY() {
        if (!isBackgroundRenderingEnabled()) {
            return;
        }

        if (vramAddress.fineY() < 7) {
            vramAddress.setFineY(vramAddress.fineY() + 1);
        } else {
            vramAddress.setFineY(0);
            if (vramAddress.coarseY() == 29) {
                vramAddress.setCoarseY(0);
                vramAddress.setNametableY(vramAddress.nametableY() ^ 0x01);
            } else if (vramAddress.coarseY() == 31) {
                vramAddress.setCoarseY(0);
            } else {
                vramAddress.setCoarseY((vramAddress.coarseY() + 1) & 0x1F);
            }
        }
    }

    private void transferAddressX() {
        if (!isBackgroundRenderingEnabled()) {
            return;
        }

        vramAddress.setNametableX(tramAddress.nametableX());
        vramAddress.setCoarseX(tramAddress.coarseX());
    }

    private void transferAddressY() {
        if (!isBackgroundRenderingEnabled()) {
            return;
        }

        vramAddress.setFineY(tramAddress.fineY());
        vramAddress.setNametableY(tramAddress.nametableY());
        vramAddress.setCoarseY(tramAddress.coarseY());
    }

    private int resolveNameTable(int address) {
        int result;
        switch (mirrorMode) {
            case VERTICAL -> result = (address & 0x0400) != 0 ? 1 : 0;
            case HORIZONTAL -> result = (address & 0x0800) != 0 ? 1 : 0;
            case ONE_SCREEN_HI -> result = 1;
            case ONE_SCREEN_LO -> result = 0;
            default -> result = 0;
        }
        return result;
    }

    private int remapPaletteAddress(int address) {
        int paletteAddress = address & 0x1F;
        if (paletteAddress == 0x10 || paletteAddress == 0x14
                || paletteAddress == 0x18 || paletteAddress == 0x1C) {
            paletteAddress -= 0x10;
        }
        return paletteAddress;
    }

    private Color getColorFromPaletteRam(int palette, int pixel) {
        pixel &= 0x03;
        palette &= 0x07;
        int index = paletteTable[(palette << 2) | pixel] & 0x3F;
        return paletteScreen[index];
    }

    private void initializePalette() {
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

    private static final class LoopyRegister {
        private int value;

        int get() {
            return value & 0x7FFF;
        }

        void set(int newValue) {
            value = newValue & 0x7FFF;
        }

        int coarseX() {
            return value & 0x1F;
        }

        void setCoarseX(int coarseX) {
            value = (value & ~0x1F) | (coarseX & 0x1F);
        }

        int coarseY() {
            return (value >> 5) & 0x1F;
        }

        void setCoarseY(int coarseY) {
            value = (value & ~(0x1F << 5)) | ((coarseY & 0x1F) << 5);
        }

        int nametableX() {
            return (value >> 10) & 0x01;
        }

        void setNametableX(int nametableX) {
            value = (value & ~(1 << 10)) | ((nametableX & 0x01) << 10);
        }

        int nametableY() {
            return (value >> 11) & 0x01;
        }

        void setNametableY(int nametableY) {
            value = (value & ~(1 << 11)) | ((nametableY & 0x01) << 11);
        }

        int fineY() {
            return (value >> 12) & 0x07;
        }

        void setFineY(int fineY) {
            value = (value & ~(0x07 << 12)) | ((fineY & 0x07) << 12);
        }
    }
}
