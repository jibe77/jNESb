package org.jnesb.bus;

import org.jnesb.cartridge.Cartridge;
import org.jnesb.cpu.Cpu6502;
import org.jnesb.cpu.CpuBus;
import org.jnesb.ppu.Ppu2C02;

/**
 * Java port of the OneLoneCoder NES system bus (OLC-3 license).
 * Responsible for routing CPU/PPU memory access and advancing the system clock.
 */
public final class NesBus implements CpuBus {

    private final Cpu6502 cpu = new Cpu6502();
    private final Ppu2C02 ppu = new Ppu2C02();
    private final int[] cpuRam = new int[2048];

    private Cartridge cartridge;
    private long systemClockCounter = 0;

    public NesBus() {
        cpu.connectBus(this);
    }

    public Cpu6502 cpu() {
        return cpu;
    }

    public Ppu2C02 ppu() {
        return ppu;
    }

    public void insertCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
        ppu.connectCartridge(cartridge);
    }

    public void reset() {
        cpu.reset();
        systemClockCounter = 0;
        ppu.clearFrameFlag();
    }

    public void clock() {
        ppu.clock();
        if (systemClockCounter % 3 == 0) {
            cpu.clock();
        }
        systemClockCounter++;
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
        }
    }
}
