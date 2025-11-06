package org.jnesb.cartridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class Mapper0Test {

    @Test
    void cpuReadMirrors16kPrg() {
        Mapper0 mapper = new Mapper0(1, 1);

        assertEquals(0x0000, mapper.cpuMapRead(0x8000));
        assertEquals(0x3FFF, mapper.cpuMapRead(0xBFFF));
        assertEquals(0x0000, mapper.cpuMapRead(0xC000));
        assertEquals(0x3FFF, mapper.cpuMapRead(0xFFFF));
    }

    @Test
    void cpuReadSpans32kPrgWhenAvailable() {
        Mapper0 mapper = new Mapper0(2, 1);

        assertEquals(0x0000, mapper.cpuMapRead(0x8000));
        assertEquals(0x7FFF, mapper.cpuMapRead(0xFFFF));
    }

    @Test
    void ppuReadDirectlyMapsChr() {
        Mapper0 mapper = new Mapper0(1, 1);

        assertEquals(0x0000, mapper.ppuMapRead(0x0000));
        assertEquals(0x1FFF, mapper.ppuMapRead(0x1FFF));
    }

    @Test
    void ppuWriteHitsChrRamWhenNoChrBanks() {
        Mapper0 mapper = new Mapper0(1, 0);

        assertEquals(0x000A, mapper.ppuMapWrite(0x000A));
        assertEquals(-1, mapper.ppuMapWrite(0x1FFF + 1));
    }
}
