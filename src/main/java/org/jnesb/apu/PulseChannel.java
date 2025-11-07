package org.jnesb.apu;

final class PulseChannel {

    private static final int[][] DUTY_CYCLES = {
            {0, 1, 0, 0, 0, 0, 0, 0},
            {0, 1, 1, 0, 0, 0, 0, 0},
            {0, 1, 1, 1, 1, 0, 0, 0},
            {1, 0, 0, 1, 1, 1, 1, 1}
    };

    private final boolean onesComplementNegate;
    private final Envelope envelope = new Envelope();
    private final LengthCounter lengthCounter = new LengthCounter();

    private boolean enabled;
    private int dutyMode;
    private int dutyStep;
    private int timerReload;
    private int timerCounter;

    // Sweep unit registers/state
    private boolean sweepEnabled;
    private int sweepPeriod;
    private boolean sweepNegate;
    private int sweepShift;
    private int sweepDivider;
    private boolean sweepReload;
    private boolean sweepMuted;

    PulseChannel(boolean onesComplementNegate) {
        this.onesComplementNegate = onesComplementNegate;
    }

    void writeControl(int data) {
        dutyMode = (data >> 6) & 0x03;
        envelope.write(data);
        lengthCounter.setHalt(envelope.isLoopEnabled());
    }

    void writeSweep(int data) {
        sweepEnabled = (data & 0x80) != 0;
        sweepPeriod = (data >> 4) & 0x07;
        sweepNegate = (data & 0x08) != 0;
        sweepShift = data & 0x07;
        sweepReload = true;
        refreshSweepMute();
    }

    void writeTimerLow(int data) {
        timerReload = (timerReload & 0x0700) | (data & 0xFF);
        refreshSweepMute();
    }

    void writeTimerHigh(int data) {
        timerReload = (timerReload & 0x00FF) | ((data & 0x07) << 8);
        lengthCounter.load((data >> 3) & 0x1F);
        envelope.start();
        dutyStep = 0;
        timerCounter = timerReload;
        refreshSweepMute();
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            lengthCounter.clear();
        }
    }

    void clock() {
        if (timerCounter == 0) {
            timerCounter = timerReload;
            dutyStep = (dutyStep + 1) & 0x07;
        } else {
            timerCounter--;
        }
    }

    void clockQuarterFrame() {
        envelope.clock();
    }

    void clockHalfFrame() {
        lengthCounter.clock(enabled);
        clockSweep();
    }

    int output() {
        if (!enabled || !lengthCounter.isActive() || sweepMuted || timerReload < 8) {
            return 0;
        }
        int[] duty = DUTY_CYCLES[dutyMode];
        if (duty[dutyStep] == 0) {
            return 0;
        }
        return envelope.output();
    }

    boolean isActive() {
        return enabled && lengthCounter.isActive() && !sweepMuted && timerReload >= 8;
    }

    private void clockSweep() {
        if (sweepDivider == 0) {
            sweepDivider = sweepPeriod;
            if (sweepEnabled && sweepShift > 0 && !sweepMuted) {
                int target = calculateSweepTarget();
                if (target >= 0 && target <= 0x7FF) {
                    timerReload = target;
                    timerCounter = timerReload;
                }
            }
        } else {
            sweepDivider--;
        }

        if (sweepReload) {
            sweepDivider = sweepPeriod;
            sweepReload = false;
        }
        refreshSweepMute();
    }

    private int calculateSweepTarget() {
        int change = timerReload >> sweepShift;
        if (sweepNegate) {
            int adjustment = onesComplementNegate ? 1 : 0;
            return timerReload - change - adjustment;
        }
        return timerReload + change;
    }

    private void refreshSweepMute() {
        boolean timerInvalid = timerReload < 8;
        boolean overflow = false;
        if (sweepEnabled && sweepShift > 0) {
            int target = calculateSweepTarget();
            overflow = target < 0 || target > 0x7FF;
        }
        sweepMuted = timerInvalid || overflow;
    }
}
