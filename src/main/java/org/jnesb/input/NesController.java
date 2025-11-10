package org.jnesb.input;

import java.util.EnumMap;
import java.util.Map;

/**
 * Models a standard NES controller with the 8-button shift register interface.
 */
public final class NesController {

    public enum Button {
        A,
        B,
        SELECT,
        START,
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    private final Map<Button, Boolean> buttonStates = new EnumMap<>(Button.class);
    private boolean strobe;
    private int shiftRegister;

    public NesController() {
        reset();
    }

    public void reset() {
        for (Button button : Button.values()) {
            buttonStates.put(button, false);
        }
        strobe = false;
        shiftRegister = 0;
    }

    public void setButton(Button button, boolean pressed) {
        buttonStates.put(button, pressed);
        if (strobe) {
            primeShiftRegister();
        }
    }

    public void setStrobe(boolean enabled) {
        if (strobe && !enabled) {
            primeShiftRegister();
        }
        strobe = enabled;
        if (strobe) {
            primeShiftRegister();
        }
    }

    public int read() {
        if (strobe) {
            primeShiftRegister();
            return shiftRegister & 0x01;
        }
        int value = shiftRegister & 0x01;
        shiftRegister >>>= 1;
        shiftRegister |= 0x80;
        return value;
    }

    private void primeShiftRegister() {
        shiftRegister = 0;
        shiftRegister |= booleanToBit(Button.A) << 0;
        shiftRegister |= booleanToBit(Button.B) << 1;
        shiftRegister |= booleanToBit(Button.SELECT) << 2;
        shiftRegister |= booleanToBit(Button.START) << 3;
        shiftRegister |= booleanToBit(Button.UP) << 4;
        shiftRegister |= booleanToBit(Button.DOWN) << 5;
        shiftRegister |= booleanToBit(Button.LEFT) << 6;
        shiftRegister |= booleanToBit(Button.RIGHT) << 7;
    }

    private int booleanToBit(Button button) {
        return Boolean.TRUE.equals(buttonStates.get(button)) ? 1 : 0;
    }

    public Map<Button, Boolean> snapshotButtonStates() {
        return new EnumMap<>(buttonStates);
    }
}
