package org.jnesb.apu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

final class DmcChannelSmoothingTest {

    @Test
    void outputSmoothingPreventsFullStepJumps() {
        byte[] memory = new byte[0x10000];
        Arrays.fill(memory, (byte) 0xFF);
        DmcChannel channel = new DmcChannel(addr -> memory[addr & 0xFFFF] & 0xFF);
        channel.reset();
        channel.writeControl(0x00);
        channel.writeSampleAddress(0x00);
        channel.writeSampleLength(0x00);
        channel.setEnabled(true);

        for (int i = 0; i < 200; i++) {
            channel.clock();
        }
        channel.setEnabled(false);
        double beforeFade = channel.output();
        for (int i = 0; i < 200; i++) {
            channel.clock();
        }
        double afterFade = channel.output();

        assertTrue(beforeFade > afterFade, "Output should decay once channel is disabled");
        assertTrue(afterFade > 0.0, "Decay should be gradual, not instantaneous");
    }
}
