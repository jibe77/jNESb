package org.jnesb.cartridge;

import java.util.Objects;

import org.jnesb.cartridge.Cartridge.Mirror;

/**
 * Base class for NES memory mappers. A mapper translates CPU/PPU address bus
 * accesses into PRG/CHR offsets inside a cartridge.
 *
 * <p>This is a direct Java port of the OneLoneCoder olcNES mapper abstraction
 * (OLC-3 license).</p>
 */
public abstract class Mapper {

    protected final int prgBanks;
    protected final int chrBanks;
    private MirrorListener mirrorListener;

    protected Mapper(int prgBanks, int chrBanks) {
        this.prgBanks = prgBanks;
        this.chrBanks = chrBanks;
    }

    /**
     * Maps a CPU address (0x8000-0xFFFF) into a PRG ROM/RAM offset.
     *
     * @return mapped address or {@code -1} if this mapper does not respond
     */
    public abstract int cpuMapRead(int address);

    /**
     * Maps a CPU write address into a PRG offset.
     *
     * @return mapped address or {@code -1} if this mapper does not respond
     */
    public abstract int cpuMapWrite(int address, int data);

    /**
     * Maps a PPU address (0x0000-0x1FFF) into a CHR ROM/RAM offset.
     *
     * @return mapped address or {@code -1} if this mapper does not respond
     */
    public abstract int ppuMapRead(int address);

    /**
     * Maps a PPU write address into a CHR offset.
     *
     * @return mapped address or {@code -1} if this mapper does not respond
     */
    public abstract int ppuMapWrite(int address);

    public void setMirrorListener(MirrorListener listener) {
        this.mirrorListener = Objects.requireNonNull(listener, "listener");
    }

    protected void notifyMirrorChange(Mirror mirror) {
        if (mirrorListener != null && mirror != null) {
            mirrorListener.onMirrorChange(mirror);
        }
    }

    @FunctionalInterface
    public interface MirrorListener {
        void onMirrorChange(Mirror mirror);
    }
}
