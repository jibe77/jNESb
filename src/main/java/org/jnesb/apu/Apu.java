package org.jnesb.apu;

import java.util.function.IntUnaryOperator;

public final class Apu {

    public static final int QUARTER_FRAME_PERIOD = 7457;

    private final FrameSequencer frameSequencer = new FrameSequencer();
    private final PulseChannel pulse1 = new PulseChannel(true);
    private final PulseChannel pulse2 = new PulseChannel(false);
    private final TriangleChannel triangle = new TriangleChannel();
    private final NoiseChannel noise = new NoiseChannel();
    private final DmcChannel dmc;

    private int statusRegister;
    private boolean irqInhibit;
    private boolean irqPending;
    private int quarterFrameCount;
    private int halfFrameCount;

    public Apu() {
        this(address -> 0);
    }

    public Apu(IntUnaryOperator dmcReader) {
        this.dmc = new DmcChannel(dmcReader);
    }

    public void reset() {
        frameSequencer.reset();
        statusRegister = 0;
        irqPending = false;
        quarterFrameCount = 0;
        halfFrameCount = 0;
        pulse1.setEnabled(false);
        pulse2.setEnabled(false);
        triangle.setEnabled(false);
        noise.setEnabled(false);
        dmc.reset();
    }

    public void clock() {
        pulse1.clock();
        pulse2.clock();
        triangle.clockTimer();
        noise.clockTimer();
        dmc.clock();

        FrameEvent event = frameSequencer.tick();
        if (event.quarterFrame()) {
            quarterFrameCount++;
            pulse1.clockQuarterFrame();
            pulse2.clockQuarterFrame();
            triangle.quarterFrame();
            noise.quarterFrame();
        }
        if (event.halfFrame()) {
            halfFrameCount++;
            pulse1.clockHalfFrame();
            pulse2.clockHalfFrame();
            triangle.halfFrame();
            noise.halfFrame();
        }
        if (event.irq() && !irqInhibit) {
            irqPending = true;
        }
    }

    public void cpuWrite(int address, int data) {
        int register = address & 0x1F;
        switch (register) {
            case 0x00 -> pulse1.writeControl(data);
            case 0x01 -> pulse1.writeSweep(data);
            case 0x02 -> pulse1.writeTimerLow(data);
            case 0x03 -> pulse1.writeTimerHigh(data);
            case 0x04 -> pulse2.writeControl(data);
            case 0x05 -> pulse2.writeSweep(data);
            case 0x06 -> pulse2.writeTimerLow(data);
            case 0x07 -> pulse2.writeTimerHigh(data);
            case 0x08 -> triangle.writeControl(data);
            case 0x0A -> triangle.writeTimerLow(data);
            case 0x0B -> triangle.writeTimerHigh(data);
            case 0x0C -> noise.writeControl(data);
            case 0x0E -> noise.writePeriod(data);
            case 0x0F -> noise.writeLength(data);
            case 0x10 -> dmc.writeControl(data);
            case 0x11 -> dmc.writeDirectLoad(data);
            case 0x12 -> dmc.writeSampleAddress(data);
            case 0x13 -> dmc.writeSampleLength(data);
            case 0x15 -> {
                statusRegister = data & 0x1F;
                pulse1.setEnabled((data & 0x01) != 0);
                pulse2.setEnabled((data & 0x02) != 0);
                triangle.setEnabled((data & 0x04) != 0);
                noise.setEnabled((data & 0x08) != 0);
                dmc.setEnabled((data & 0x10) != 0);
                irqPending = false;
            }
            case 0x17 -> {
                irqInhibit = (data & 0x40) != 0;
                if (irqInhibit) {
                    irqPending = false;
                }
                frameSequencer.setMode((data & 0x80) != 0 ? FrameSequencer.Mode.FIVE_STEP
                        : FrameSequencer.Mode.FOUR_STEP);
            }
            default -> {
            }
        }
    }

    public int cpuRead(int address) {
        int register = address & 0x1F;
        if (register == 0x15) {
            int value = 0;
            if (pulse1.isActive()) {
                value |= 0x01;
            }
            if (pulse2.isActive()) {
                value |= 0x02;
            }
            if (triangle.isActive()) {
                value |= 0x04;
            }
            if (noise.isActive()) {
                value |= 0x08;
            }
            if (dmc.isActive()) {
                value |= 0x10;
            }
            if (irqPending && !irqInhibit) {
                value |= 0x40;
            }
            if (dmc.isIrqPending()) {
                value |= 0x80;
            }
            irqPending = false;
            dmc.clearIrq();
            return value;
        }
        return 0;
    }

    public boolean pollIrq() {
        boolean frameIrq = irqPending && !irqInhibit;
        boolean dmcIrq = dmc.isIrqPending();
        if (frameIrq || dmcIrq) {
            if (frameIrq) {
                irqPending = false;
            }
            if (dmcIrq) {
                dmc.clearIrq();
            }
            return true;
        }
        return false;
    }

    public int quarterFrameCount() {
        return quarterFrameCount;
    }

    public int halfFrameCount() {
        return halfFrameCount;
    }

    public boolean isIrqPending() {
        return irqPending && !irqInhibit;
    }

    public double sample() {
        int pulseSum = pulse1.output() + pulse2.output();
        int triangleSample = triangle.output();
        int noiseSample = noise.output();
        int dmcSample = dmc.output();

        double pulseComponent = pulseSum == 0
                ? 0.0
                : 95.88 / ((8128.0 / pulseSum) + 100.0);

        double tndInput = (triangleSample / 8227.0)
                + (noiseSample / 12241.0)
                + (dmcSample / 22638.0);
        double tndComponent = tndInput == 0.0
                ? 0.0
                : 159.79 / ((1.0 / tndInput) + 100.0);

        return pulseComponent + tndComponent;
    }

    private static final class FrameSequencer {

        private static final int MAX_FOUR_STEP = 4;
        private static final int MAX_FIVE_STEP = 5;

        private Mode mode = Mode.FOUR_STEP;
        private int cyclesUntilStep = QUARTER_FRAME_PERIOD;
        private int stepIndex = 0;

        FrameEvent tick() {
            if (--cyclesUntilStep > 0) {
                return FrameEvent.NONE;
            }

            cyclesUntilStep = QUARTER_FRAME_PERIOD;

            boolean quarter = true;
            boolean half = (stepIndex == 1 || stepIndex == 3);
            boolean irq = mode == Mode.FOUR_STEP && stepIndex == 3;

            stepIndex++;
            int max = mode == Mode.FOUR_STEP ? MAX_FOUR_STEP : MAX_FIVE_STEP;
            if (stepIndex >= max) {
                stepIndex = 0;
            }

            return new FrameEvent(quarter, half, irq);
        }

        void reset() {
            mode = Mode.FOUR_STEP;
            cyclesUntilStep = QUARTER_FRAME_PERIOD;
            stepIndex = 0;
        }

        void setMode(Mode mode) {
            if (this.mode != mode) {
                this.mode = mode;
                cyclesUntilStep = QUARTER_FRAME_PERIOD;
                stepIndex = 0;
            }
        }

        enum Mode {
            FOUR_STEP,
            FIVE_STEP
        }
    }

    private record FrameEvent(boolean quarterFrame, boolean halfFrame, boolean irq) {
        static final FrameEvent NONE = new FrameEvent(false, false, false);
    }
}
