package org.jnesb.cartridge;

/**
 * Mapper 066 (GxROM) implementation with simple PRG/CHR bank selection.
 */
public final class Mapper66 extends Mapper {

    private int chrBankSelect;
    private int prgBankSelect;

    public Mapper66(int prgBanks, int chrBanks) {
        super(prgBanks, chrBanks);
    }

    @Override
    public int cpuMapRead(int address) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            int bank = prgBankSelect % Math.max(prgBanks / 2, 1);
            return bank * 0x8000 + (address & 0x7FFF);
        }
        return -1;
    }

    @Override
    public int cpuMapWrite(int address, int data) {
        if (address >= 0x8000 && address <= 0xFFFF) {
            chrBankSelect = data & 0x03;
            prgBankSelect = (data >> 4) & 0x03;
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
        prgBankSelect = 0;
    }
}
