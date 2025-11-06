package org.jnesb.apu;

final class PulseChannel {

    private static final int[][] DUTY_CYCLES = {
            {0, 1, 0, 0, 0, 0, 0, 0},
            {0, 1, 1, 0, 0, 0, 0, 0},
            {0, 1, 1, 1, 1, 0, 0, 0},
            {1, 0, 0, 1, 1, 1, 1, 1}
    };

    private final Envelope envelope = new Envelope();
    private final LengthCounter lengthCounter = new LengthCounter();
    private final SweepUnit sweep;

    private boolean enabled;
    private int dutyMode;
    private int dutyStep;
    private int timer;
    private int timerReload;

    PulseChannel(boolean negateOnesComplement) {
        sweep = new SweepUnit(negateOnesComplement);
    }

    void writeControl(int data) {
        dutyMode = (data >> 6) & 0x03;
        envelope.write(data);
        lengthCounter.setHalt(envelope.isLoopEnabled());
    }

    void writeSweep(int data) {
        sweep.write(data);
    }

    void writeTimerLow(int data) {
        timerReload = (timerReload & 0xFF00) | (data & 0xFF);
    }

    void writeTimerHigh(int data) {
        timerReload = (timerReload & 0x00FF) | ((data & 0x07) << 8);
        lengthCounter.load((data >> 3) & 0x1F);
        envelope.start();
        dutyStep = 0;
        timer = timerReload;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            lengthCounter.clear();
        }
    }

    void clockTimer() {
        if (timer == 0) {
            timer = timerReload;
            dutyStep = (dutyStep + 1) & 0x07;
        } else {
            timer--;
        }
    }

    void quarterFrame() {
        envelope.clock();
    }

    void halfFrame() {
        lengthCounter.clock(enabled);
        sweep.clock(this);
    }

    int output() {
        if (!enabled || !lengthCounter.isActive() || timerReload < 8) {
            return 0;
        }
        int[] duty = DUTY_CYCLES[dutyMode];
        if (duty[dutyStep] == 0) {
            return 0;
        }
        return envelope.output();
    }

    int timer() {
        return timerReload;
    }

    void setTimer(int value) {
        timerReload = value & 0x7FF;
    }

    boolean isActive() {
        return enabled && lengthCounter.isActive();
    }
}
