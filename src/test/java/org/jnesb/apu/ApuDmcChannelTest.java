package org.jnesb.apu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ApuDmcChannelTest {

    @Test
    void statusReflectsDmcActivity() {
        byte[] memory = new byte[0x10000];
        memory[0xC000] = (byte) 0xFF;
        Apu apu = new Apu(addr -> memory[addr & 0xFFFF] & 0xFF);
        apu.reset();

        apu.cpuWrite(0x4010, 0x00);
        apu.cpuWrite(0x4012, 0x00);
        apu.cpuWrite(0x4013, 0x00);
        apu.cpuWrite(0x4015, 0x10);

        assertEquals(0x10, apu.cpuRead(0x4015) & 0x10);

        apu.cpuWrite(0x4015, 0x00);
        assertEquals(0x00, apu.cpuRead(0x4015) & 0x10);
    }

    @Test
    void dmcRaisesIrqWhenSampleEnds() {
        byte[] memory = new byte[0x10000];
        memory[0xC000] = 0x00;
        Apu apu = new Apu(addr -> memory[addr & 0xFFFF] & 0xFF);
        apu.reset();

        apu.cpuWrite(0x4010, 0x80); // IRQ enabled, rate index 0
        apu.cpuWrite(0x4012, 0x00);
        apu.cpuWrite(0x4013, 0x00); // 1 byte sample
        apu.cpuWrite(0x4015, 0x10);

        int cycles = 428 * 8 + 10;
        for (int i = 0; i < cycles; i++) {
            apu.clock();
        }

        assertTrue(apu.pollIrq());
    }
}
