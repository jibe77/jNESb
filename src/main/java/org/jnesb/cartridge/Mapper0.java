package org.jnesb.cartridge;

/**
 * Mapper 000 (NROM) implementation. Supports 16KB and 32KB PRG images and
 * optional CHR RAM (when {@code chrBanks == 0}).
 */
public final class Mapper0 extends Mapper {

    public Mapper0(int prgBanks, int chrBanks) {
        super(prgBanks, chrBanks);
    }

    @Override
    public int cpuMapRead(int address) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            return address & (prgBanks > 1 ? 0x7FFF : 0x3FFF);
        }
        return -1;
    }

    @Override
    public int cpuMapWrite(int address) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            return address & (prgBanks > 1 ? 0x7FFF : 0x3FFF);
        }
        return -1;
    }

    @Override
    public int ppuMapRead(int address) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            return address;
        }
        return -1;
    }

    @Override
    public int ppuMapWrite(int address) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            if (chrBanks == 0) {
                return address;
            }
        }
        return -1;
    }
}
