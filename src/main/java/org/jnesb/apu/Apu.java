package org.jnesb.apu;

import java.nio.ByteBuffer;
import java.util.function.IntUnaryOperator;

import org.jnesb.state.Stateful;

/**
 * APU amélioré avec timing plus précis et mixage audio correct
 */
public final class Apu implements Stateful {

    // State size for APU core state
    private static final int STATE_SIZE = 64;

    // Le CPU de la NES tourne à ~1.789773 MHz
    // L'APU génère des échantillons à cette fréquence divisée par 2
    public static final double CPU_CLOCK_RATE = 1789773.0;
    public static final int QUARTER_FRAME_PERIOD = 7457;

    private final FrameSequencer frameSequencer = new FrameSequencer();
    private final PulseChannel pulse1 = new PulseChannel(true);
    private final PulseChannel pulse2 = new PulseChannel(false);
    private final TriangleChannel triangle = new TriangleChannel();
    private final NoiseChannel noise = new NoiseChannel();
    private final DmcChannel dmc;

    private boolean irqInhibit;
    private boolean irqPending;
    private int quarterFrameCount;
    private int halfFrameCount;
    private int statusRegister;
    
    // Filtre passe-haut pour éliminer le DC offset
    private double highPassAccumulator = 0.0;
    private static final double HIGH_PASS_ALPHA = 0.999835; // Cutoff ~90Hz à 44100Hz
    
    // Filtre passe-bas pour anti-aliasing
    private double lowPassPrev = 0.0;
    private static final double LOW_PASS_ALPHA = 0.815686; // Cutoff ~12kHz à 44100Hz

    // Cycle counter pour un timing plus précis
    private int cpuCycles = 0;

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
        irqInhibit = false;
        quarterFrameCount = 0;
        halfFrameCount = 0;
        cpuCycles = 0;
        pulse1.setEnabled(false);
        pulse2.setEnabled(false);
        triangle.setEnabled(false);
        noise.setEnabled(false);
        dmc.reset();
        highPassAccumulator = 0.0;
        lowPassPrev = 0.0;
    }

    /**
     * Clock l'APU. Doit être appelé à chaque cycle CPU.
     */
    public void clock() {
        cpuCycles++;
        
        // Triangle et DMC sont clockés à chaque cycle CPU
        triangle.clockTimer();
        dmc.clock();
        
        // Les générateurs de formes d'onde sont clockés tous les 2 cycles CPU
        // Sur les cycles pairs (0, 2, 4, ...) on clock pulse et noise
        if ((cpuCycles & 1) == 0) {
            pulse1.clock();
            pulse2.clock();
            noise.clockTimer();
        }

        // Frame counter
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
                boolean newMode = (data & 0x80) != 0;
                frameSequencer.setMode(newMode ? FrameSequencer.Mode.FIVE_STEP
                        : FrameSequencer.Mode.FOUR_STEP);
                
                // En mode 5-step, clock immédiatement les enveloppes et length counters
                if (newMode) {
                    pulse1.clockQuarterFrame();
                    pulse2.clockQuarterFrame();
                    triangle.quarterFrame();
                    noise.quarterFrame();
                    pulse1.clockHalfFrame();
                    pulse2.clockHalfFrame();
                    triangle.halfFrame();
                    noise.halfFrame();
                }
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

    /**
     * Génère un échantillon audio avec mixage non-linéaire précis et filtrage
     * TEMP: Filtrage désactivé pour debug
     */
    public double sample() {
        // Utilisation des tables de mixage non-linéaire de la NES
        // Ces formules reproduisent fidèlement le comportement du DAC hardware
        
        int pulse1Out = pulse1.output();
        int pulse2Out = pulse2.output();
        int pulseSum = pulse1Out + pulse2Out;
        
        // Formule précise pour les canaux pulse
        double pulseOutput = 0.0;
        if (pulseSum > 0) {
            pulseOutput = 95.88 / ((8128.0 / pulseSum) + 100.0);
        }
        
        // Canaux TND (Triangle, Noise, DMC)
        int triangleOut = triangle.output();
        int noiseOut = noise.output();
        int dmcOut = (int) dmc.output();
        
        // Formule précise pour les canaux TND
        double tndOutput = 0.0;
        double tndSum = (triangleOut / 8227.0) + (noiseOut / 12241.0) + (dmcOut / 22638.0);
        if (tndSum > 0) {
            tndOutput = 159.79 / ((1.0 / tndSum) + 100.0);
        }

        // Mixage final SANS filtrage pour debug
        double mixed = pulseOutput + tndOutput;
        
        // Normalisation finale
        return mixed * 0.5;
    }
    
    /**
     * Version avec filtrage (à utiliser quand le debug est terminé)
     */
    public double sampleFiltered() {
        // Utilisation des tables de mixage non-linéaire de la NES
        int pulse1Out = pulse1.output();
        int pulse2Out = pulse2.output();
        int pulseSum = pulse1Out + pulse2Out;
        
        double pulseOutput = 0.0;
        if (pulseSum > 0) {
            pulseOutput = 95.88 / ((8128.0 / pulseSum) + 100.0);
        }
        
        int triangleOut = triangle.output();
        int noiseOut = noise.output();
        int dmcOut = (int) dmc.output();
        
        double tndOutput = 0.0;
        double tndSum = (triangleOut / 8227.0) + (noiseOut / 12241.0) + (dmcOut / 22638.0);
        if (tndSum > 0) {
            tndOutput = 159.79 / ((1.0 / tndSum) + 100.0);
        }
        
        double mixed = pulseOutput + tndOutput;
        
        // Application du filtre passe-haut pour éliminer le DC offset
        // Cela évite les "clics" et le son trop grave
        double highPassInput = mixed;
        double highPassOutput = highPassInput - highPassAccumulator;
        highPassAccumulator = highPassInput - highPassOutput * HIGH_PASS_ALPHA;
        
        // Application du filtre passe-bas pour anti-aliasing
        // Cela réduit les harmoniques haute fréquence stridentes
        double lowPassOutput = lowPassPrev + LOW_PASS_ALPHA * (highPassOutput - lowPassPrev);
        lowPassPrev = lowPassOutput;
        
        // Normalisation finale (la sortie est entre -1.0 et 1.0)
        // Le volume peut être ajusté ici si nécessaire
        return lowPassOutput * 0.5; // Réduction légère du volume pour éviter la saturation
    }

    /**
     * Version alternative sans filtrage pour debug
     */
    public double sampleRaw() {
        int pulseSum = pulse1.output() + pulse2.output();
        double pulse = pulseSum == 0
                ? 0.0
                : 95.88 / ((8128.0 / pulseSum) + 100.0);

        int triangleSample = triangle.output();
        int noiseSample = noise.output();
        int dmcSample = (int) dmc.output();

        double tndInput = (triangleSample / 8227.0)
                + (noiseSample / 12241.0)
                + (dmcSample / 22638.0);
        double tndComponent = tndInput == 0.0
                ? 0.0
                : 159.79 / ((1.0 / tndInput) + 100.0);

        return pulse + tndComponent;
    }

    public int getCpuCycles() {
        return cpuCycles;
    }

    @Override
    public byte[] saveState() {
        ByteBuffer buffer = ByteBuffer.allocate(STATE_SIZE);

        // Core APU state
        buffer.put((byte) (irqInhibit ? 1 : 0));
        buffer.put((byte) (irqPending ? 1 : 0));
        buffer.putInt(quarterFrameCount);
        buffer.putInt(halfFrameCount);
        buffer.put((byte) statusRegister);
        buffer.putInt(cpuCycles);

        // Filter state
        buffer.putDouble(highPassAccumulator);
        buffer.putDouble(lowPassPrev);

        return buffer.array();
    }

    @Override
    public void loadState(byte[] data) {
        if (data == null || data.length < STATE_SIZE) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);

        // Core APU state
        irqInhibit = buffer.get() != 0;
        irqPending = buffer.get() != 0;
        quarterFrameCount = buffer.getInt();
        halfFrameCount = buffer.getInt();
        statusRegister = buffer.get() & 0xFF;
        cpuCycles = buffer.getInt();

        // Filter state
        highPassAccumulator = buffer.getDouble();
        lowPassPrev = buffer.getDouble();

        // Re-apply status register to channels
        pulse1.setEnabled((statusRegister & 0x01) != 0);
        pulse2.setEnabled((statusRegister & 0x02) != 0);
        triangle.setEnabled((statusRegister & 0x04) != 0);
        noise.setEnabled((statusRegister & 0x08) != 0);
        dmc.setEnabled((statusRegister & 0x10) != 0);
    }

    @Override
    public int stateSize() {
        return STATE_SIZE;
    }

    private static final class FrameSequencer {

        private static final int MAX_FOUR_STEP = 4;
        private static final int MAX_FIVE_STEP = 5;

        // Timing précis du frame counter en cycles CPU
        // Mode 4-step: 7457, 14913, 22371, 29829 (29830 avec IRQ)
        // Mode 5-step: 7457, 14913, 22371, 37281, 37282
        private static final int[] FOUR_STEP_CYCLES = {7457, 14913, 22371, 29829, 29830};
        private static final int[] FIVE_STEP_CYCLES = {7457, 14913, 22371, 37281, 37282};

        private Mode mode = Mode.FOUR_STEP;
        private int cycleCounter = 0;
        private int stepIndex = 0;

        FrameEvent tick() {
            cycleCounter++;
            
            int[] cycles = mode == Mode.FOUR_STEP ? FOUR_STEP_CYCLES : FIVE_STEP_CYCLES;
            
            if (cycleCounter < cycles[stepIndex]) {
                return FrameEvent.NONE;
            }

            boolean quarter = false;
            boolean half = false;
            boolean irq = false;

            // Mode 4-step
            if (mode == Mode.FOUR_STEP) {
                switch (stepIndex) {
                    case 0, 1, 2 -> {
                        quarter = true;
                        half = (stepIndex == 1);
                    }
                    case 3 -> {
                        quarter = true;
                        half = true;
                    }
                    case 4 -> {
                        irq = true;
                        cycleCounter = 0;
                        stepIndex = 0;
                        return new FrameEvent(false, false, irq);
                    }
                }
            }
            // Mode 5-step
            else {
                switch (stepIndex) {
                    case 0, 2 -> quarter = true;
                    case 1, 3 -> {
                        quarter = true;
                        half = true;
                    }
                    case 4 -> {
                        cycleCounter = 0;
                        stepIndex = 0;
                        return FrameEvent.NONE;
                    }
                }
            }

            stepIndex++;
            return new FrameEvent(quarter, half, irq);
        }

        void reset() {
            mode = Mode.FOUR_STEP;
            cycleCounter = 0;
            stepIndex = 0;
        }

        void setMode(Mode newMode) {
            this.mode = newMode;
            cycleCounter = 0;
            stepIndex = 0;
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