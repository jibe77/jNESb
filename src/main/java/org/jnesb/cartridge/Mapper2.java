package org.jnesb.cartridge;

/**
 * Mapper 002 (UxROM) implementation with 16KB switchable bank.
 */
public final class Mapper2 extends Mapper {

    private int prgBankSelectLo;
    private int prgBankSelectHi;

    public Mapper2(int prgBanks, int chrBanks) {
        super(prgBanks, chrBanks);
        reset();
    }

    @Override
    public int cpuMapRead(int address) {
        if (address >= 0x8000 && address <= 0xBFFF) {
            int bank = prgBankSelectLo % Math.max(prgBanks, 1);
            return bank * 0x4000 + (address & 0x3FFF);
        }
        if (address >= 0xC000 && address <= 0xFFFF) {
            int bank = prgBankSelectHi % Math.max(prgBanks, 1);
            return bank * 0x4000 + (address & 0x3FFF);
        }
        return -1;
    }

    @Override
    public int cpuMapWrite(int address, int data) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            prgBankSelectLo = data & 0x0F;
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
        if (chrBanks == 0 && address >= 0x0000 && address <= 0x1FFF) {
            return address;
        }
        return -1;
    }

    @Override
    public void reset() {
        prgBankSelectLo = 0;
        prgBankSelectHi = Math.max(prgBanks - 1, 0);
    }
}
