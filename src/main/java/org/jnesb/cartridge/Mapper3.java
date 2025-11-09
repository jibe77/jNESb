package org.jnesb.cartridge;

/**
 * Mapper 003 (CNROM) implementation with CHR bank switching.
 */
public final class Mapper3 extends Mapper {

    private int chrBankSelect;

    public Mapper3(int prgBanks, int chrBanks) {
        super(prgBanks, chrBanks);
    }

    @Override
    public int cpuMapRead(int address) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            if (prgBanks == 1) {
                return address & 0x3FFF;
            }
            return address & 0x7FFF;
        }
        return -1;
    }

    @Override
    public int cpuMapWrite(int address, int data) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            chrBankSelect = data & 0x03;
        }
        return -1;
    }

    @Override
    public int ppuMapRead(int address) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            int bank = chrBankSelect % Math.max(chrBanks, 1);
            return bank * 0x2000 + (address & 0x1FFF);
        }
        return -1;
    }

    @Override
    public int ppuMapWrite(int address) {
        if (chrBanks == 0 && address >= 0x0000 && address <= 0x1FFF) {
            return address;
        }
        return -1;
    }

    @Override
    public void reset() {
        chrBankSelect = 0;
    }
}
