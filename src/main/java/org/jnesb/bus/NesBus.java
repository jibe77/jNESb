package org.jnesb.bus;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

import org.jnesb.AudioConfig;
import org.jnesb.apu.Apu;
import org.jnesb.cartridge.Cartridge;
import org.jnesb.cpu.Cpu6502;
import org.jnesb.cpu.CpuBus;
import org.jnesb.input.NesController;
import org.jnesb.input.NesZapper;
import org.jnesb.ppu.Ppu2C02;

/**
 * Java port of the OneLoneCoder NES system bus (OLC-3 license).
 * Responsible for routing CPU/PPU memory access and advancing the system clock.
 */
public final class NesBus implements CpuBus {

    // Save state format constants
    private static final byte[] MAGIC = {'j', 'N', 'E', 'S'};
    private static final short VERSION = 3;
    private static final int HEADER_SIZE = 4 + 2 + 4 + 4; // magic + version + checksum + romChecksum

    private static final int AUDIO_BUFFER_CAPACITY = 4096;

    private final Cpu6502 cpu = new Cpu6502();
    private final Ppu2C02 ppu = new Ppu2C02();
    private final NesZapper zapper = NesZapper.attachedTo(ppu);
    private final Apu apu;
    private final int[] cpuRam = new int[2048];
    private final NesController[] controllers = {new NesController(), new NesController()};
    private final double[] audioSamples = new double[AUDIO_BUFFER_CAPACITY];
    private final Object audioBufferLock = new Object();

    private Cartridge cartridge;
    private long systemClockCounter = 0;
    private double audioCycleAccumulator = 0.0;
    private int audioSampleWriteIndex = 0;
    private int audioSampleReadIndex = 0;
    private int audioSampleCount = 0;

    public NesBus() {
        apu = new Apu(this::dmcRead);
        cpu.connectBus(this);
    }

    public Cpu6502 cpu() {
        return cpu;
    }

    public Ppu2C02 ppu() {
        return ppu;
    }

    public Apu apu() {
        return apu;
    }

    public NesZapper zapper() {
        return zapper;
    }

    public void insertCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
        ppu.connectCartridge(cartridge);
        cartridge.setMirrorConsumer(ppu::setMirrorMode);
    }
    public NesController controller(int index) {
        return controllers[index & 1];
    }

    /**
     * Saves the complete emulator state to a byte array with structured format.
     * Format: [MAGIC:4][VERSION:2][CRC32:4][CPU_STATE][PPU_STATE][APU_STATE][CPU_RAM][CARTRIDGE_STATE][SYSTEM_CLOCK:8]
     */
    public byte[] saveMemoryState() {
        // Get component states
        byte[] cpuState = cpu.saveState();
        byte[] ppuState = ppu.saveState();
        byte[] apuState = apu.saveState();
        byte[] cartridgeState = cartridge != null ? cartridge.saveState() : new byte[0];

        // Convert CPU RAM to bytes
        byte[] cpuRamBytes = new byte[cpuRam.length];
        for (int i = 0; i < cpuRam.length; i++) {
            cpuRamBytes[i] = (byte) (cpuRam[i] & 0xFF);
        }

        // Calculate total size
        int payloadSize = 4 + cpuState.length +      // CPU state size + data
                         4 + ppuState.length +       // PPU state size + data
                         4 + apuState.length +       // APU state size + data
                         4 + cpuRamBytes.length +    // CPU RAM size + data
                         4 + cartridgeState.length + // Cartridge state size + data
                         8;                          // System clock counter

        int totalSize = HEADER_SIZE + payloadSize;
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);

        // Write header (checksum placeholder)
        buffer.put(MAGIC);
        buffer.putShort(VERSION);
        int checksumPosition = buffer.position();
        buffer.putInt(0); // Placeholder for checksum
        // Write ROM checksum for game validation
        int romChecksum = cartridge != null ? cartridge.romChecksum() : 0;
        buffer.putInt(romChecksum);

        // Write payload
        buffer.putInt(cpuState.length);
        buffer.put(cpuState);

        buffer.putInt(ppuState.length);
        buffer.put(ppuState);

        buffer.putInt(apuState.length);
        buffer.put(apuState);

        buffer.putInt(cpuRamBytes.length);
        buffer.put(cpuRamBytes);

        buffer.putInt(cartridgeState.length);
        buffer.put(cartridgeState);

        buffer.putLong(systemClockCounter);

        // Calculate and write checksum
        byte[] result = buffer.array();
        CRC32 crc = new CRC32();
        crc.update(result, HEADER_SIZE, payloadSize);
        int checksum = (int) crc.getValue();
        ByteBuffer.wrap(result).position(checksumPosition).putInt(checksum);

        return result;
    }

    /**
     * Loads the emulator state from a byte array with structured format validation.
     * @throws IllegalStateException if the save state is for a different game
     */
    public void loadMemoryState(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);

        // Verify magic
        byte[] magic = new byte[4];
        buffer.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            // Try legacy format (old save files)
            loadLegacyState(data);
            return;
        }

        // Read version and checksum
        short version = buffer.getShort();
        int storedChecksum = buffer.getInt();

        // Read ROM checksum (version 3+)
        int storedRomChecksum = 0;
        if (version >= 3) {
            storedRomChecksum = buffer.getInt();
        }

        // Calculate actual header size based on version
        int actualHeaderSize = (version >= 3) ? HEADER_SIZE : (HEADER_SIZE - 4);

        // Verify checksum
        CRC32 crc = new CRC32();
        crc.update(data, actualHeaderSize, data.length - actualHeaderSize);
        int calculatedChecksum = (int) crc.getValue();
        if (storedChecksum != calculatedChecksum) {
            return; // Corrupted file
        }

        // Validate ROM checksum (version 3+)
        if (version >= 3 && cartridge != null && storedRomChecksum != 0) {
            int currentRomChecksum = cartridge.romChecksum();
            if (storedRomChecksum != currentRomChecksum) {
                throw new IllegalStateException(
                    "Save state was created for a different game (ROM checksum mismatch)");
            }
        }

        // Read component states from buffer (must match save order)
        int cpuStateSize = buffer.getInt();
        byte[] cpuState = new byte[cpuStateSize];
        buffer.get(cpuState);

        int ppuStateSize = buffer.getInt();
        byte[] ppuState = new byte[ppuStateSize];
        buffer.get(ppuState);

        int apuStateSize = buffer.getInt();
        byte[] apuState = new byte[apuStateSize];
        buffer.get(apuState);

        // Apply states in correct order: PPU first, then CPU
        // This prevents NMI timing issues during state restoration
        ppu.loadState(ppuState);
        cpu.loadState(cpuState);
        apu.loadState(apuState);

        int cpuRamSize = buffer.getInt();
        for (int i = 0; i < cpuRamSize && i < cpuRam.length; i++) {
            cpuRam[i] = buffer.get() & 0xFF;
        }

        int cartridgeStateSize = buffer.getInt();
        if (cartridge != null && cartridgeStateSize > 0) {
            byte[] cartridgeState = new byte[cartridgeStateSize];
            buffer.get(cartridgeState);
            cartridge.loadState(cartridgeState);
        }

        if (buffer.remaining() >= 8) {
            systemClockCounter = buffer.getLong();
        }
    }

    /**
     * Legacy state loading for backward compatibility with old save files.
     */
    private void loadLegacyState(byte[] data) {
        int offset = 0;
        for (int i = 0; i < cpuRam.length && offset < data.length; i++, offset++) {
            cpuRam[i] = data[offset] & 0xFF;
        }
        if (cartridge != null && cartridge.prgRamLength() > 0) {
            int remaining = data.length - offset;
            byte[] prgSlice = new byte[Math.min(remaining, cartridge.prgRamLength())];
            System.arraycopy(data, offset, prgSlice, 0, prgSlice.length);
            cartridge.loadPrgRam(prgSlice);
        }
    }

    public void reset() {
        cpu.reset();
        ppu.reset();
        apu.reset();
        if (cartridge != null) {
            cartridge.reset();
        }
        for (NesController controller : controllers) {
            controller.reset();
        }
        zapper.reset();
        systemClockCounter = 0;
        resetAudioSamples();
    }

    public boolean clock() {
        ppu.clock();
        if (ppu.pollNmi()) {
            cpu.nmi();
        }
        boolean cpuClocked = false;
        if (systemClockCounter % 3 == 0) {
            cpu.clock();
            apu.clock();
            accumulateAudioSample();
            if (apu.pollIrq()) {
                cpu.irq();
            }
            if (cartridge != null && cartridge.pollIrq()) {
                cartridge.clearIrq();
                cpu.irq();
            }
            cpuClocked = true;
        }
        systemClockCounter++;
        return cpuClocked;
    }

    @Override
    public int read(int address, boolean readOnly) {
        address &= 0xFFFF;

        if (cartridge != null) {
            int[] cartridgeData = new int[1];
            if (cartridge.cpuRead(address, cartridgeData)) {
                return cartridgeData[0];
            }
        }

        if (address >= 0x0000 && address <= 0x1FFF) {
            return cpuRam[address & 0x07FF];
        }

        if (address >= 0x2000 && address <= 0x3FFF) {
            return ppu.cpuRead(address & 0x0007, readOnly);
        }

        if (address == 0x4015) {
            return apu.cpuRead(address);
        }

        if (address == 0x4016 || address == 0x4017) {
            int port = address - 0x4016;
            int data = controllers[port].read() & 0x01;
            if (port == 1) {
                data |= zapper.read();
            }
            return data;
        }

        return 0x00;
    }

    @Override
    public void write(int address, int data) {
        address &= 0xFFFF;
        data &= 0xFF;

        if (cartridge != null && cartridge.cpuWrite(address, data)) {
            return;
        }

        if (address >= 0x0000 && address <= 0x1FFF) {
            cpuRam[address & 0x07FF] = data;
        } else if (address >= 0x2000 && address <= 0x3FFF) {
            ppu.cpuWrite(address & 0x0007, data);
        } else if (address == 0x4014) {
            int base = (data & 0xFF) << 8;
            for (int i = 0; i < 256; i++) {
                int value = read(base + i, false);
                ppu.dmaWrite(value);
            }
        } else if (address == 0x4016) {
            boolean strobe = (data & 0x01) != 0;
            for (NesController controller : controllers) {
                controller.setStrobe(strobe);
            }
        } else if ((address >= 0x4000 && address <= 0x4013)
                || address == 0x4015
                || address == 0x4017) {
            apu.cpuWrite(address, data);
        }
    }

    public double pollAudioSample() {
        synchronized (audioBufferLock) {
            while (audioSampleCount == 0) {
                try {
                    audioBufferLock.wait();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return 0.0;
                }
            }
            double sample = audioSamples[audioSampleReadIndex];
            audioSampleReadIndex = (audioSampleReadIndex + 1) % audioSamples.length;
            audioSampleCount--;
            audioBufferLock.notifyAll();
            return sample;
        }
    }

    private int dmcRead(int address) {
        cpu.stall(4);
        return read(address, true) & 0xFF;
    }

    private void accumulateAudioSample() {
        audioCycleAccumulator += 1.0;
        while (audioCycleAccumulator >= AudioConfig.CPU_CYCLES_PER_SAMPLE) {
            audioCycleAccumulator -= AudioConfig.CPU_CYCLES_PER_SAMPLE;
            double sample = apu.sample();
            enqueueAudioSample(sample);
        }
    }

    private void enqueueAudioSample(double sample) {
        synchronized (audioBufferLock) {
            while (audioSampleCount == audioSamples.length) {
                try {
                    audioBufferLock.wait();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            audioSamples[audioSampleWriteIndex] = sample;
            audioSampleWriteIndex = (audioSampleWriteIndex + 1) % audioSamples.length;
            audioSampleCount++;
            audioBufferLock.notifyAll();
        }
    }

    private void resetAudioSamples() {
        synchronized (audioBufferLock) {
            audioCycleAccumulator = 0.0;
            audioSampleWriteIndex = 0;
            audioSampleReadIndex = 0;
            audioSampleCount = 0;
            audioBufferLock.notifyAll();
        }
    }
}
