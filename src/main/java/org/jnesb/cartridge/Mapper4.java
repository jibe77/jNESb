package org.jnesb.cartridge;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.jnesb.cartridge.Cartridge.Mirror;

/**
 * Mapper 004 (MMC3) implementation with bank switching and IRQ counter.
 */
public final class Mapper4 extends Mapper {

    private static final int STATE_SIZE = 64;

    private final int[] registers = new int[8];
    private final int[] chrBankOffsets = new int[8];
    private final int[] prgBankOffsets = new int[4];

    private int targetRegister;
    private boolean prgBankMode;
    private boolean chrInversion;

    private boolean irqActive;
    private boolean irqEnable;
    private int irqCounter;
    private int irqReload;

    public Mapper4(int prgBanks, int chrBanks) {
        super(prgBanks, chrBanks);
    }

    @Override
    public byte[] saveState() {
        ByteBuffer buffer = ByteBuffer.allocate(STATE_SIZE);
        // Save registers
        for (int i = 0; i < 8; i++) {
            buffer.put((byte) registers[i]);
        }
        // Save bank offsets
        for (int i = 0; i < 8; i++) {
            buffer.putInt(chrBankOffsets[i]);
        }
        for (int i = 0; i < 4; i++) {
            buffer.putInt(prgBankOffsets[i]);
        }
        // Save state
        buffer.put((byte) targetRegister);
        buffer.put((byte) (prgBankMode ? 1 : 0));
        buffer.put((byte) (chrInversion ? 1 : 0));
        buffer.put((byte) (irqActive ? 1 : 0));
        buffer.put((byte) (irqEnable ? 1 : 0));
        buffer.put((byte) irqCounter);
        buffer.put((byte) irqReload);
        return buffer.array();
    }

    @Override
    public void loadState(byte[] data) {
        if (data == null || data.length < STATE_SIZE) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        // Load registers
        for (int i = 0; i < 8; i++) {
            registers[i] = buffer.get() & 0xFF;
        }
        // Load bank offsets
        for (int i = 0; i < 8; i++) {
            chrBankOffsets[i] = buffer.getInt();
        }
        for (int i = 0; i < 4; i++) {
            prgBankOffsets[i] = buffer.getInt();
        }
        // Load state
        targetRegister = buffer.get() & 0xFF;
        prgBankMode = buffer.get() != 0;
        chrInversion = buffer.get() != 0;
        irqActive = buffer.get() != 0;
        irqEnable = buffer.get() != 0;
        irqCounter = buffer.get() & 0xFF;
        irqReload = buffer.get() & 0xFF;
    }

    @Override
    public int stateSize() {
        return STATE_SIZE;
    }

    @Override
    public int cpuMapRead(int address) {
        if (address >= 0x8000 && address <= 0x9FFF) {
            return prgBankOffsets[0] + (address & 0x1FFF);
        }
        if (address >= 0xA000 && address <= 0xBFFF) {
            return prgBankOffsets[1] + (address & 0x1FFF);
        }
        if (address >= 0xC000 && address <= 0xDFFF) {
            return prgBankOffsets[2] + (address & 0x1FFF);
        }
        if (address >= 0xE000 && address <= 0xFFFF) {
            return prgBankOffsets[3] + (address & 0x1FFF);
        }
        return -1;
    }

    @Override
    public int cpuMapWrite(int address, int data) {
        if (address >= 0x8000 && address <= 0x9FFF) {
            if ((address & 0x0001) == 0) {
                targetRegister = data & 0x07;
                prgBankMode = (data & 0x40) != 0;
                chrInversion = (data & 0x80) != 0;
            } else {
                registers[targetRegister & 0x07] = data & 0xFF;
                updateChrBanks();
                updatePrgBanks();
            }
            return -1;
        }

        if (address >= 0xA000 && address <= 0xBFFF) {
            if ((address & 0x0001) == 0) {
                notifyMirrorChange((data & 0x01) != 0 ? Mirror.HORIZONTAL : Mirror.VERTICAL);
            } else {
                // PRG RAM protect not modeled.
            }
            return -1;
        }

        if (address >= 0xC000 && address <= 0xDFFF) {
            if ((address & 0x0001) == 0) {
                irqReload = data & 0xFF;
            } else {
                irqCounter = 0;
            }
            return -1;
        }

        if (address >= 0xE000 && address <= 0xFFFF) {
            if ((address & 0x0001) == 0) {
                irqEnable = false;
                irqActive = false;
            } else {
                irqEnable = true;
            }
            return -1;
        }

        return -1;
    }

    @Override
    public int ppuMapRead(int address) {
        if (address >= 0x0000 && address <= 0x1FFF) {
            int bank = (address >> 10) & 0x07;
            return chrBankOffsets[bank] + (address & 0x03FF);
        }
        return -1;
    }

    @Override
    public int ppuMapWrite(int address) {
        if (chrBanks == 0 && address >= 0x0000 && address <= 0x1FFF) {
            int bank = (address >> 10) & 0x07;
            return chrBankOffsets[bank] + (address & 0x03FF);
        }
        return -1;
    }

    @Override
    public void reset() {
        targetRegister = 0;
        prgBankMode = false;
        chrInversion = false;
        irqActive = false;
        irqEnable = false;
        irqCounter = 0;
        irqReload = 0;
        Arrays.fill(registers, 0);
        Arrays.fill(chrBankOffsets, 0);
        Arrays.fill(prgBankOffsets, 0);

        // Default PRG layout: first 2x8KB switchable + fixed last banks.
        prgBankOffsets[0] = wrapPrgBank(0);
        prgBankOffsets[1] = wrapPrgBank(1);
        prgBankOffsets[2] = wrapPrgBank(Math.max(prgBanks * 2 - 2, 0));
        prgBankOffsets[3] = wrapPrgBank(Math.max(prgBanks * 2 - 1, 0));
        updateChrBanks();
    }

    @Override
    public void onScanline() {
        if (irqCounter == 0) {
            irqCounter = irqReload;
        } else {
            irqCounter--;
        }
        if (irqCounter == 0 && irqEnable) {
            irqActive = true;
        }
    }

    @Override
    public boolean isIrqAsserted() {
        return irqActive;
    }

    @Override
    public void clearIrq() {
        irqActive = false;
    }

    private void updatePrgBanks() {
        int selectedBank = registers[6] & 0x3F;
        int bankTwo = Math.max(prgBanks * 2 - 2, 0);
        int bankThree = Math.max(prgBanks * 2 - 1, 0);

        if (prgBankMode) {
            prgBankOffsets[0] = wrapPrgBank(bankTwo);
            prgBankOffsets[2] = wrapPrgBank(selectedBank);
        } else {
            prgBankOffsets[0] = wrapPrgBank(selectedBank);
            prgBankOffsets[2] = wrapPrgBank(bankTwo);
        }

        prgBankOffsets[1] = wrapPrgBank(registers[7] & 0x3F);
        prgBankOffsets[3] = wrapPrgBank(bankThree);
    }

    private void updateChrBanks() {
        if (chrInversion) {
            chrBankOffsets[0] = wrapChrBank(registers[2]);
            chrBankOffsets[1] = wrapChrBank(registers[3]);
            chrBankOffsets[2] = wrapChrBank(registers[4]);
            chrBankOffsets[3] = wrapChrBank(registers[5]);
            chrBankOffsets[4] = wrapChrBank(registers[0] & 0xFE);
            chrBankOffsets[5] = wrapChrBank((registers[0] & 0xFE) + 1);
            chrBankOffsets[6] = wrapChrBank(registers[1] & 0xFE);
            chrBankOffsets[7] = wrapChrBank((registers[1] & 0xFE) + 1);
        } else {
            chrBankOffsets[0] = wrapChrBank(registers[0] & 0xFE);
            chrBankOffsets[1] = wrapChrBank((registers[0] & 0xFE) + 1);
            chrBankOffsets[2] = wrapChrBank(registers[1] & 0xFE);
            chrBankOffsets[3] = wrapChrBank((registers[1] & 0xFE) + 1);
            chrBankOffsets[4] = wrapChrBank(registers[2]);
            chrBankOffsets[5] = wrapChrBank(registers[3]);
            chrBankOffsets[6] = wrapChrBank(registers[4]);
            chrBankOffsets[7] = wrapChrBank(registers[5]);
        }
    }

    private int wrapPrgBank(int bank) {
        int count = Math.max(prgBanks * 2, 1);
        int wrapped = ((bank % count) + count) % count;
        return wrapped * 0x2000;
    }

    private int wrapChrBank(int bank) {
        int count = Math.max(chrBanks * 8, 8);
        int wrapped = ((bank % count) + count) % count;
        return wrapped * 0x0400;
    }
}
