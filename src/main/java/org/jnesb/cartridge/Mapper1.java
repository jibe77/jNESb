package org.jnesb.cartridge;

import org.jnesb.cartridge.Cartridge.Mirror;

/**
 * Mapper 001 (MMC1) implementation supporting PRG/CHR bank switching.
 */
public final class Mapper1 extends Mapper {

    private int shiftRegister = 0x10;
    private int writeCount = 0;

    private int control = 0x0C;
    private int chrBank0 = 0;
    private int chrBank1 = 0;
    private int prgBank = 0;

    public Mapper1(int prgBanks, int chrBanks) {
        super(prgBanks, chrBanks);
    }

    @Override
    public int cpuMapRead(int address) {
        if (address < 0x8000 || address > 0xFFFF) {
            return -1;
        }

        int prgMode = (control >> 2) & 0x03;
        int bank = 0;
        if (prgMode == 0 || prgMode == 1) {
            bank = (prgBank & 0x0E) % Math.max(prgBanks, 1);
            return (bank * 0x4000) + (address & 0x7FFF);
        } else if (prgMode == 2) {
            if (address >= 0xC000) {
                bank = prgBank & 0x0F;
                bank %= Math.max(prgBanks, 1);
                return (bank * 0x4000) + (address & 0x3FFF);
            }
            return address & 0x3FFF;
        } else {
            if (address < 0xC000) {
                bank = prgBank & 0x0F;
                bank %= Math.max(prgBanks, 1);
                return (bank * 0x4000) + (address & 0x3FFF);
            }
            bank = Math.max(prgBanks - 1, 0);
            return (bank * 0x4000) + (address & 0x3FFF);
        }
    }

    @Override
    public int cpuMapWrite(int address, int data) {
        if (address < 0x8000 || address > 0xFFFF) {
            return -1;
        }

        if ((data & 0x80) != 0) {
            shiftRegister = 0x10;
            writeCount = 0;
            control |= 0x0C;
            notifyMirrorChange(decodeMirror(control & 0x03));
            return -1;
        }

        shiftRegister = (shiftRegister >> 1) | ((data & 0x01) << 4);
        writeCount++;

        if (writeCount == 5) {
            int target = (address >> 13) & 0x03;
            int value = shiftRegister & 0x1F;
            loadRegister(target, value);
            shiftRegister = 0x10;
            writeCount = 0;
        }
        return -1;
    }

    @Override
    public int ppuMapRead(int address) {
        if (address < 0x0000 || address > 0x1FFF) {
            return -1;
        }

        int bankMode = (control >> 4) & 0x01;
        int chrRamBanks = Math.max(chrBanks, 1);
        if (bankMode == 0) {
            int bank = (chrBank0 & 0x1E) % (chrRamBanks * 2);
            return (bank * 0x1000) + (address & 0x1FFF);
        } else {
            if (address < 0x1000) {
                int bank = chrBank0 % (chrRamBanks * 2);
                return (bank * 0x1000) + (address & 0x0FFF);
            } else {
                int bank = chrBank1 % (chrRamBanks * 2);
                return (bank * 0x1000) + (address & 0x0FFF);
            }
        }
    }

    @Override
    public int ppuMapWrite(int address) {
        if (chrBanks == 0 && address >= 0x0000 && address <= 0x1FFF) {
            return address;
        }
        return -1;
    }

    private void loadRegister(int target, int value) {
        switch (target) {
            case 0 -> {
                control = value & 0x1F;
                notifyMirrorChange(decodeMirror(control & 0x03));
            }
            case 1 -> chrBank0 = value & 0x1F;
            case 2 -> chrBank1 = value & 0x1F;
            case 3 -> prgBank = value & 0x0F;
            default -> {
            }
        }
    }

    private Mirror decodeMirror(int mode) {
        return switch (mode & 0x03) {
            case 0 -> Mirror.ONE_SCREEN_LO;
            case 1 -> Mirror.ONE_SCREEN_HI;
            case 2 -> Mirror.VERTICAL;
            default -> Mirror.HORIZONTAL;
        };
    }
}
