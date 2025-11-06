package org.jnesb.cartridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicReference;

import org.jnesb.cartridge.Cartridge.Mirror;
import org.junit.jupiter.api.Test;

final class Mapper1Test {

    @Test
    void prgModeDefaultFixesLastBank() {
        Mapper1 mapper = new Mapper1(4, 2);

        assertEquals(0x0000, mapper.cpuMapRead(0x8000));
        assertEquals((3 * 0x4000) + 0x1234, mapper.cpuMapRead(0xC000 + 0x1234));
    }

    @Test
    void prgModeSwitchesUpperBank() {
        Mapper1 mapper = new Mapper1(4, 2);

        writeRegister(mapper, 0x8000, 0x08); // set control -> mode 2
        writeRegister(mapper, 0xE000, 0x02); // set PRG bank

        assertEquals(0x0000, mapper.cpuMapRead(0x8000));
        int expected = (2 * 0x4000) + 0x00FF;
        assertEquals(expected, mapper.cpuMapRead(0xC000 + 0x00FF));
    }

    @Test
    void chrModeSplitBanks() {
        Mapper1 mapper = new Mapper1(2, 2);

        writeRegister(mapper, 0x8000, 0x10); // CHR mode = 1
        writeRegister(mapper, 0xA000, 0x02); // CHR bank 0
        writeRegister(mapper, 0xC000, 0x03); // CHR bank 1

        assertEquals((2 * 0x1000) + 0x00AA, mapper.ppuMapRead(0x00AA));
        assertEquals((3 * 0x1000) + 0x0800, mapper.ppuMapRead(0x1800));
    }

    @Test
    void controlUpdatesMirrorMode() {
        Mapper1 mapper = new Mapper1(2, 2);
        AtomicReference<Mirror> mirrorRef = new AtomicReference<>();
        mapper.setMirrorListener(mirrorRef::set);

        writeRegister(mapper, 0x8000, 0x00); // single-screen low
        assertEquals(Mirror.ONE_SCREEN_LO, mirrorRef.get());

        writeRegister(mapper, 0x8000, 0x02); // vertical
        assertEquals(Mirror.VERTICAL, mirrorRef.get());

        writeRegister(mapper, 0x8000, 0x03); // horizontal
        assertEquals(Mirror.HORIZONTAL, mirrorRef.get());
    }

    private static void writeRegister(Mapper1 mapper, int address, int value) {
        for (int i = 0; i < 5; i++) {
            int bit = (value >> i) & 0x01;
            mapper.cpuMapWrite(address, bit);
        }
    }
}
