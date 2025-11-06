package org.jnesb.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.jnesb.input.NesController;
import org.jnesb.input.NesController.Button;
import org.junit.jupiter.api.Test;

final class NesBusControllerTest {

    @Test
    void cpuReadReturnsControllerShiftBits() {
        NesBus bus = new NesBus();
        NesController controller1 = bus.controller(0);
        controller1.setButton(Button.A, true);
        controller1.setButton(Button.START, true);

        bus.write(0x4016, 1);
        bus.write(0x4016, 0);

        int[] expected = {1, 0, 0, 1};
        for (int value : expected) {
            assertEquals(value, bus.read(0x4016, false));
        }
    }

    @Test
    void controllerTwoUses4017() {
        NesBus bus = new NesBus();
        NesController controller2 = bus.controller(1);
        controller2.setButton(Button.A, true);

        bus.write(0x4016, 1);
        bus.write(0x4016, 0);

        assertEquals(0, bus.read(0x4016, false));
        assertEquals(1, bus.read(0x4017, false));
    }
}
