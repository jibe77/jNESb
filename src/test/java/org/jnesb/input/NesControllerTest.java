package org.jnesb.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.jnesb.input.NesController.Button;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class NesControllerTest {

    private NesController controller;

    @BeforeEach
    void setUp() {
        controller = new NesController();
    }

    @Test
    void readSequenceMatchesButtonOrder() {
        controller.setButton(Button.A, true);
        controller.setButton(Button.RIGHT, true);

        controller.setStrobe(true);
        controller.setStrobe(false);

        int[] expected = {1, 0, 0, 0, 0, 0, 0, 1};
        for (int value : expected) {
            assertEquals(value, controller.read());
        }

        assertEquals(1, controller.read(), "further reads should return 1");
    }

    @Test
    void strobeHighReturnsCurrentAState() {
        controller.setButton(Button.A, false);
        controller.setStrobe(true);
        assertEquals(0, controller.read());

        controller.setButton(Button.A, true);
        assertEquals(1, controller.read());

        controller.setButton(Button.A, false);
        assertEquals(0, controller.read());
    }
}
