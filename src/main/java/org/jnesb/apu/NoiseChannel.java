package org.jnesb.apu;

final class NoiseChannel {

    private static final int[] PERIOD_TABLE = {
            4, 8, 16, 32, 64, 96, 128, 160,
            202, 254, 380, 508, 762, 1016, 2034, 4068
    };

    private final Envelope envelope = new Envelope();
    private final LengthCounter lengthCounter = new LengthCounter();

    private boolean enabled;
    private boolean shortMode;
    private int timerPeriod = PERIOD_TABLE[0];
    private int timerCounter;
    private int shiftRegister = 1;

    void writeControl(int data) {
        envelope.write(data);
        lengthCounter.setHalt(envelope.isLoopEnabled());
    }

    void writePeriod(int data) {
        shortMode = (data & 0x80) != 0;
        int index = data & 0x0F;
        if (index < PERIOD_TABLE.length) {
            timerPeriod = PERIOD_TABLE[index];
        }
    }

    void writeLength(int data) {
        lengthCounter.load((data >> 3) & 0x1F);
        envelope.start();
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            lengthCounter.clear();
        }
    }

    void clockTimer() {
        if (!enabled || !lengthCounter.isActive()) {
            return;
        }
        if (timerCounter == 0) {
            timerCounter = timerPeriod;
            int bit0 = shiftRegister & 0x01;
            int tapBit = (shiftRegister >> (shortMode ? 6 : 1)) & 0x01;
            int feedback = bit0 ^ tapBit;
            shiftRegister >>>= 1;
            shiftRegister |= (feedback << 14);
        } else {
            timerCounter--;
        }
    }

    void quarterFrame() {
        envelope.clock();
    }

    void halfFrame() {
        lengthCounter.clock(enabled);
    }

    int output() {
        if (!enabled || !lengthCounter.isActive()) {
            return 0;
        }
        if ((shiftRegister & 0x01) != 0) {
            return 0;
        }
        return envelope.output();
    }

    boolean isActive() {
        return enabled && lengthCounter.isActive();
    }
}
