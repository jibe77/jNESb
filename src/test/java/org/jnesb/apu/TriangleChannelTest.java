package org.jnesb.apu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TriangleChannelTest {

    @Test
    void linearCounterGatesOutput() {
        TriangleChannel triangle = new TriangleChannel();
        triangle.setEnabled(true);

        triangle.writeControl(0x80 | 0x02); // control flag set, linear reload = 2
        triangle.writeTimerLow(0xFF);
        triangle.writeTimerHigh(0x07);

        triangle.quarterFrame(); // reload -> linear counter = 2
        triangle.clockTimer();
        triangle.clockTimer();
        triangle.clockTimer();

        assertEquals(15, triangle.output());

        triangle.quarterFrame(); // linear counter stays at reload value (control flag set)
        triangle.setEnabled(false);
        assertEquals(0, triangle.output());
    }
}
