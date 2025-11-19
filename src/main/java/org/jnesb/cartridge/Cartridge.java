package org.jnesb.cartridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.CRC32;

import org.jnesb.state.Stateful;

/**
 * Java port of the OneLoneCoder olcNES Cartridge abstraction (OLC-3 license).
 * Handles loading iNES images and delegating address translation to a mapper.
 */
public final class Cartridge implements Stateful {

    // Base state size (not including dynamic mapper state)
    private static final int BASE_STATE_SIZE = 16;

    public enum Mirror {
        HORIZONTAL,
        VERTICAL,
        ONE_SCREEN_LO,
        ONE_SCREEN_HI
    }

    private final int mapperId;
    private final int prgBanks;
    private final int chrBanks;
    private final byte[] prgMemory;
    private final byte[] prgRam;
    private final byte[] chrMemory;
    private final Mapper mapper;
    private Mirror mirror;
    private final boolean imageValid;
    private final int romChecksum;
    private Consumer<Mirror> mirrorConsumer;

    private Cartridge(int mapperId,
                      int prgBanks,
                      int chrBanks,
                      byte[] prgMemory,
                      byte[] prgRam,
                      byte[] chrMemory,
                      Mapper mapper,
                      Mirror mirror,
                      boolean imageValid,
                      int romChecksum) {
        this.mapperId = mapperId;
        this.prgBanks = prgBanks;
        this.chrBanks = chrBanks;
        this.prgMemory = prgMemory;
        this.prgRam = prgRam;
        this.chrMemory = chrMemory;
        this.mapper = mapper;
        this.mirror = mirror;
        this.imageValid = imageValid;
        this.romChecksum = romChecksum;
        if (this.mapper != null) {
            this.mapper.setMirrorListener(this::applyMirror);
            this.mapper.reset();
        }
    }

    public static Cartridge load(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return load(is);
        }
    }

    public static Cartridge load(InputStream inputStream) throws IOException {
        Header header = Header.read(inputStream);

        if (!header.valid) {
            return new Cartridge(0, 0, 0, new byte[0], new byte[0], new byte[0], null, Mirror.HORIZONTAL, false, 0);
        }

        // Skip trainer if present
        if ((header.mapper1 & 0x04) != 0) {
            skipFully(inputStream, 512);
        }

        int mapperId = ((header.mapper2 >> 4) << 4) | (header.mapper1 >> 4);
        Mirror mirror = (header.mapper1 & 0x01) != 0 ? Mirror.VERTICAL : Mirror.HORIZONTAL;

        // iNES 1.0
        int prgBanks = header.prgRomChunks & 0xFF;
        byte[] prgMemory = readFully(inputStream, prgBanks * 16 * 1024);
        byte[] prgRam = new byte[8 * 1024];

        int chrBanks = header.chrRomChunks & 0xFF;
        byte[] chrMemory = readFully(inputStream, chrBanks * 8 * 1024);
        if (chrBanks == 0) {
            // CHR RAM
            chrMemory = new byte[8 * 1024];
        }

        Mapper mapper = switch (mapperId) {
            case 0 -> new Mapper0(prgBanks, chrBanks);
            case 1 -> new Mapper1(prgBanks, chrBanks);
            case 2 -> new Mapper2(prgBanks, chrBanks);
            case 3 -> new Mapper3(prgBanks, chrBanks);
            case 4 -> new Mapper4(prgBanks, chrBanks);
            case 66 -> new Mapper66(prgBanks, chrBanks);
            default -> throw new IOException("Unsupported mapper: " + mapperId);
        };

        // Calculate ROM checksum for save state validation
        CRC32 crc = new CRC32();
        crc.update(prgMemory);
        if (chrBanks > 0) {
            crc.update(chrMemory);
        }
        int romChecksum = (int) crc.getValue();

        return new Cartridge(mapperId, prgBanks, chrBanks, prgMemory, prgRam, chrMemory, mapper, mirror, true, romChecksum);
    }

    public boolean isImageValid() {
        return imageValid;
    }

    public Mirror mirror() {
        return mirror;
    }

    public void reset() {
        if (mapper != null) {
            mapper.reset();
        }
    }

    public void scanline() {
        if (mapper != null) {
            mapper.onScanline();
        }
    }

    public boolean pollIrq() {
        return mapper != null && mapper.isIrqAsserted();
    }

    public void clearIrq() {
        if (mapper != null) {
            mapper.clearIrq();
        }
    }

    public void setMirrorConsumer(Consumer<Mirror> consumer) {
        this.mirrorConsumer = consumer;
        if (consumer != null) {
            consumer.accept(mirror);
        }
    }

    public int mapperId() {
        return mapperId;
    }

    public int romChecksum() {
        return romChecksum;
    }

    public boolean cpuRead(int address, int[] dataOut) {
        address &= 0xFFFF;
        int mapped = mapper.cpuMapRead(address);
        if (mapped >= 0) {
            dataOut[0] = prgMemory[mapped] & 0xFF;
            return true;
        }
        if (address >= 0x6000 && address <= 0x7FFF) {
            dataOut[0] = prgRam[address & 0x1FFF] & 0xFF;
            return true;
        }
        return false;
    }

    public boolean cpuWrite(int address, int value) {
        address &= 0xFFFF;
        value &= 0xFF;
        int mapped = mapper.cpuMapWrite(address, value);
        if (mapped >= 0) {
            prgMemory[mapped] = (byte) value;
            return true;
        }
        if (address >= 0x6000 && address <= 0x7FFF) {
            prgRam[address & 0x1FFF] = (byte) value;
            return true;
        }
        return false;
    }

    public boolean ppuRead(int address, int[] dataOut) {
        int mapped = mapper.ppuMapRead(address & 0xFFFF);
        if (mapped >= 0 && mapped < chrMemory.length) {
            dataOut[0] = chrMemory[mapped] & 0xFF;
            return true;
        }
        return false;
    }

    public boolean ppuWrite(int address, int value) {
        int mapped = mapper.ppuMapWrite(address & 0xFFFF);
        if (mapped >= 0 && mapped < chrMemory.length) {
            chrMemory[mapped] = (byte) value;
            return true;
        }
        return false;
    }

    public int prgBankCount() {
        return prgBanks;
    }

    public int chrBankCount() {
        return chrBanks;
    }

    public byte[] copyPrgRam() {
        return Arrays.copyOf(prgRam, prgRam.length);
    }

    public void loadPrgRam(byte[] data) {
        if (data == null) {
            return;
        }
        System.arraycopy(data, 0, prgRam, 0, Math.min(prgRam.length, data.length));
    }

    public int prgRamLength() {
        return prgRam.length;
    }

    @Override
    public byte[] saveState() {
        // Calculate total size
        byte[] mapperState = mapper != null ? mapper.saveState() : new byte[0];
        int totalSize = BASE_STATE_SIZE + prgRam.length + chrMemory.length + mapperState.length;

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);

        // Header info
        buffer.putInt(prgRam.length);
        buffer.putInt(chrMemory.length);
        buffer.putInt(mapperState.length);
        buffer.put((byte) mirror.ordinal());

        // PRG RAM
        buffer.put(prgRam);

        // CHR Memory (for CHR RAM games)
        buffer.put(chrMemory);

        // Mapper state
        buffer.put(mapperState);

        return buffer.array();
    }

    @Override
    public void loadState(byte[] data) {
        if (data == null || data.length < BASE_STATE_SIZE) {
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);

        // Read header
        int prgRamSize = buffer.getInt();
        int chrMemSize = buffer.getInt();
        int mapperStateSize = buffer.getInt();
        int mirrorOrdinal = buffer.get() & 0xFF;

        // Validate sizes
        if (prgRamSize != prgRam.length || chrMemSize != chrMemory.length) {
            return; // Size mismatch, incompatible state
        }

        // Load PRG RAM
        buffer.get(prgRam);

        // Load CHR Memory
        buffer.get(chrMemory);

        // Load mapper state
        if (mapper != null && mapperStateSize > 0) {
            byte[] mapperState = new byte[mapperStateSize];
            buffer.get(mapperState);
            mapper.loadState(mapperState);
        }

        // Restore mirror
        Mirror[] mirrors = Mirror.values();
        if (mirrorOrdinal < mirrors.length) {
            mirror = mirrors[mirrorOrdinal];
            if (mirrorConsumer != null) {
                mirrorConsumer.accept(mirror);
            }
        }
    }

    @Override
    public int stateSize() {
        int mapperSize = mapper != null ? mapper.stateSize() : 0;
        return BASE_STATE_SIZE + prgRam.length + chrMemory.length + mapperSize;
    }

    private void applyMirror(Mirror newMirror) {
        if (newMirror == null || newMirror == mirror) {
            return;
        }
        mirror = newMirror;
        if (mirrorConsumer != null) {
            mirrorConsumer.accept(newMirror);
        }
    }

    private static final class Header {
        final boolean valid;
        final byte mapper1;
        final byte mapper2;
        final byte prgRomChunks;
        final byte chrRomChunks;

        private Header(boolean valid, byte mapper1, byte mapper2, byte prgRomChunks, byte chrRomChunks) {
            this.valid = valid;
            this.mapper1 = mapper1;
            this.mapper2 = mapper2;
            this.prgRomChunks = prgRomChunks;
            this.chrRomChunks = chrRomChunks;
        }

        static Header read(InputStream inputStream) throws IOException {
            byte[] raw = readFully(inputStream, 16);
            if (raw.length != 16) {
                return new Header(false, (byte) 0, (byte) 0, (byte) 0, (byte) 0);
            }
            if (!(raw[0] == 'N' && raw[1] == 'E' && raw[2] == 'S' && raw[3] == 0x1A)) {
                return new Header(false, (byte) 0, (byte) 0, (byte) 0, (byte) 0);
            }
            return new Header(true, raw[6], raw[7], raw[4], raw[5]);
        }
    }

    private static byte[] readFully(InputStream inputStream, int size) throws IOException {
        byte[] data = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = inputStream.read(data, offset, size - offset);
            if (read == -1) {
                return Arrays.copyOf(data, offset);
            }
            offset += read;
        }
        return data;
    }

    private static void skipFully(InputStream inputStream, int length) throws IOException {
        long remaining = length;
        while (remaining > 0) {
            long skipped = inputStream.skip(remaining);
            if (skipped <= 0) {
                if (inputStream.read() == -1) {
                    break;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }
}
