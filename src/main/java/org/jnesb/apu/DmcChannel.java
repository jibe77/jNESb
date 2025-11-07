package org.jnesb.apu;

import java.util.function.IntUnaryOperator;

final class DmcChannel {

    private static final int[] RATE_TABLE = {
            428, 380, 340, 320, 286, 254, 226, 214,
            190, 160, 142, 128, 106, 85, 72, 54
    };

    private final IntUnaryOperator memoryReader;

    private boolean enabled;
    private boolean irqEnabled;
    private boolean loopSample;
    private int rateIndex;

    private int timerPeriod = RATE_TABLE[0];
    private int timerCounter = RATE_TABLE[0];
    private int outputLevel;

    private int sampleAddress = 0xC000;
    private int sampleLength = 1;
    private int currentAddress = sampleAddress;
    private int bytesRemaining;

    private int sampleBuffer = -1;
    private int shiftRegister;
    private int bitsRemaining;

    private boolean irqPending;

    DmcChannel(IntUnaryOperator memoryReader) {
        this.memoryReader = memoryReader;
    }

    void reset() {
        enabled = false;
        irqEnabled = false;
        loopSample = false;
        rateIndex = 0;
        timerPeriod = RATE_TABLE[0];
        timerCounter = timerPeriod;
        outputLevel = 0;
        sampleAddress = 0xC000;
        sampleLength = 1;
        currentAddress = sampleAddress;
        bytesRemaining = 0;
        sampleBuffer = -1;
        shiftRegister = 0;
        bitsRemaining = 0;
        irqPending = false;
    }

    void clock() {
        if (timerCounter == 0) {
            timerCounter = RATE_TABLE[rateIndex];
            stepOutput();
        } else {
            timerCounter--;
        }
        fetchSampleBuffer();
    }

    void writeControl(int data) {
        irqEnabled = (data & 0x80) != 0;
        if (!irqEnabled) {
            irqPending = false;
        }
        loopSample = (data & 0x40) != 0;
        rateIndex = data & 0x0F;
        timerPeriod = RATE_TABLE[rateIndex];
        timerCounter = 0;
    }

    void writeDirectLoad(int data) {
        outputLevel = data & 0x7F;
    }

    void writeSampleAddress(int data) {
        sampleAddress = 0xC000 | ((data & 0xFF) << 6);
    }

    void writeSampleLength(int data) {
        sampleLength = ((data & 0xFF) << 4) | 0x01;
    }

    void setEnabled(boolean enabled) {
        boolean wasDisabled = !this.enabled && enabled;
        this.enabled = enabled;
        if (!enabled) {
            bytesRemaining = 0;
            sampleBuffer = -1;
            bitsRemaining = 0;
            irqPending = false;
        } else if (wasDisabled && bytesRemaining == 0) {
            restartSample();
        }
        fetchSampleBuffer();
    }

    int output() {
        return outputLevel;
    }

    boolean isActive() {
        return bytesRemaining > 0 || sampleBuffer >= 0 || bitsRemaining > 0;
    }

    boolean isIrqPending() {
        return irqPending;
    }

    void clearIrq() {
        irqPending = false;
    }

    private void stepOutput() {
        if (bitsRemaining == 0) {
            reloadShiftRegister();
        }
        if (bitsRemaining == 0) {
            return;
        }
        int bit = shiftRegister & 0x01;
        if (bit == 1) {
            if (outputLevel <= 125) {
                outputLevel += 2;
            }
        } else {
            if (outputLevel >= 2) {
                outputLevel -= 2;
            }
        }
        shiftRegister >>>= 1;
        bitsRemaining--;
    }

    private void reloadShiftRegister() {
        if (sampleBuffer < 0) {
            return;
        }
        shiftRegister = sampleBuffer;
        bitsRemaining = 8;
        sampleBuffer = -1;
    }

    private void fetchSampleBuffer() {
        while (enabled && sampleBuffer < 0) {
            if (bytesRemaining == 0) {
                if (loopSample) {
                    restartSample();
                } else {
                    if (irqEnabled) {
                        irqPending = true;
                    }
                    return;
                }
            }
            sampleBuffer = memoryReader.applyAsInt(currentAddress & 0xFFFF) & 0xFF;
            currentAddress = nextSampleAddress(currentAddress);
            bytesRemaining--;
        }
    }

    private void restartSample() {
        currentAddress = sampleAddress;
        bytesRemaining = sampleLength;
    }

    private static int nextSampleAddress(int address) {
        address = (address + 1) & 0xFFFF;
        if (address < 0x8000) {
            address += 0x8000;
        }
        return address;
    }
}
