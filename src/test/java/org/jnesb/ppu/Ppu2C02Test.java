package org.jnesb.ppu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class Ppu2C02Test {

    @Test
    void ppudataWriteIncrementsAddressByOneOrThirtyTwo() {
        Ppu2C02 ppu = new Ppu2C02();

        // Default increment is 1
        ppu.cpuWrite(0x0000, 0x00); // Control register
        ppu.cpuWrite(0x0006, 0x20); // PPUADDR high byte -> 0x2000
        ppu.cpuWrite(0x0006, 0x00); // PPUADDR low byte
        ppu.cpuWrite(0x0007, 0x12); // Write to 0x2000
        ppu.cpuWrite(0x0007, 0x34); // Write to 0x2001 (auto-increment)

        assertEquals(0x12, ppu.ppuRead(0x2000, true));
        assertEquals(0x34, ppu.ppuRead(0x2001, true));

        // Enable increment-by-32 mode
        ppu.cpuWrite(0x0000, 0x04);
        ppu.cpuWrite(0x0006, 0x20); // Reset address to 0x2000
        ppu.cpuWrite(0x0006, 0x00);
        ppu.cpuWrite(0x0007, 0x56); // -> 0x2000
        ppu.cpuWrite(0x0007, 0x78); // -> 0x2020 (0x2000 + 32)

        assertEquals(0x56, ppu.ppuRead(0x2000, true));
        assertEquals(0x78, ppu.ppuRead(0x2020, true));
    }

    @Test
    void paletteAddressMirrorsUniversalBackgroundEntry() {
        Ppu2C02 ppu = new Ppu2C02();

        ppu.ppuWrite(0x3F00, 0x05);
        assertEquals(0x05, ppu.ppuRead(0x3F00, true));
        assertEquals(0x05, ppu.ppuRead(0x3F10, true), "0x3F10 should mirror 0x3F00");

        ppu.ppuWrite(0x3F10, 0x09);
        assertEquals(0x09, ppu.ppuRead(0x3F00, true));
        assertEquals(0x09, ppu.ppuRead(0x3F10, true));
    }

    @Test
    void clockRequestsNmiAtStartOfVerticalBlank() {
        Ppu2C02 ppu = new Ppu2C02();

        // Enable NMI on vertical blank
        ppu.cpuWrite(0x0000, 0x80);

        boolean nmiSeen = false;
        int totalCycles = 341 * 262; // Full frame including pre-render line
        for (int cycle = 0; cycle < totalCycles; cycle++) {
            ppu.clock();
            if (!nmiSeen) {
                nmiSeen = ppu.pollNmi();
            }
        }

        assertTrue(nmiSeen, "PPU should request an NMI when vertical blank starts");
        assertTrue(ppu.isFrameComplete(), "Frame completion flag should be set after a full frame");
    }

    @Test
    void getNameTableRendersPatternUsingPaletteEntries() {
        Ppu2C02 ppu = new Ppu2C02();

        // Palette 0, entry 1 -> palette color index 1
        ppu.ppuWrite(0x3F00, 0x00);
        ppu.ppuWrite(0x3F01, 0x01);
        ppu.ppuWrite(0x3F02, 0x02);
        ppu.ppuWrite(0x3F03, 0x03);

        // Pattern table tile 0: all pixels use bitplane value 1
        for (int row = 0; row < 8; row++) {
            ppu.ppuWrite(0x0000 + row, 0xFF);      // LSB
            ppu.ppuWrite(0x0000 + row + 8, 0x00);  // MSB
        }

        // Attribute table top-left quadrant uses palette 0
        ppu.ppuWrite(0x23C0, 0x00);

        Sprite sprite = ppu.getNameTable(0);
        int topLeftPixel = sprite.getPixel(0, 0);

        // Palette index 1 corresponds to RGB (0, 30, 116)
        int expected = (0 << 16) | (30 << 8) | 116;
        assertEquals(expected, topLeftPixel);
    }
}
