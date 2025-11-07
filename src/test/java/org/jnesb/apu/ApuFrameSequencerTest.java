package org.jnesb.apu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ApuFrameSequencerTest {

    @Test
    void fourStepModeGeneratesIrqAndEvents() {
        Apu apu = new Apu();
        apu.reset();
        clock(apu, Apu.QUARTER_FRAME_PERIOD * 4);

        assertEquals(4, apu.quarterFrameCount());
        assertEquals(2, apu.halfFrameCount());
        assertTrue(apu.isIrqPending());
        assertTrue(apu.pollIrq());
    }

    @Test
    void fiveStepModeSuppressesIrq() {
        Apu apu = new Apu();
        apu.reset();

        apu.cpuWrite(0x4017, 0x80);
        clock(apu, Apu.QUARTER_FRAME_PERIOD * 5);

        assertEquals(5, apu.quarterFrameCount());
        assertEquals(2, apu.halfFrameCount());
        assertFalse(apu.isIrqPending());
    }

    @Test
    void statusReadClearsIrqFlag() {
        Apu apu = new Apu();
        apu.reset();

        clock(apu, Apu.QUARTER_FRAME_PERIOD * 4);
        assertTrue(apu.isIrqPending());

        int status = apu.cpuRead(0x4015);
        assertEquals(0x40, status);
        assertFalse(apu.isIrqPending());
    }

    @Test
    void statusReflectsChannelEnables() {
        Apu apu = new Apu();
        apu.reset();

        apu.cpuWrite(0x4000, 0x30);
        apu.cpuWrite(0x4002, 0xFF);
        apu.cpuWrite(0x4003, 0x08);
        apu.cpuWrite(0x400C, 0x30);
        apu.cpuWrite(0x400E, 0x00);
        apu.cpuWrite(0x400F, 0x00);
        apu.cpuWrite(0x4015, 0x09);

        assertEquals(0x09, apu.cpuRead(0x4015) & 0x0F);

        apu.cpuWrite(0x4015, 0x00);
        assertEquals(0x00, apu.cpuRead(0x4015) & 0x0F);
    }

    private static void clock(Apu apu, int cycles) {
        for (int i = 0; i < cycles; i++) {
            apu.clock();
        }
    }
}
