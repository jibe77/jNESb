package org.jnesb.bus;

import org.jnesb.apu.Apu;
import org.jnesb.cartridge.Cartridge;
import org.jnesb.cpu.Cpu6502;
import org.jnesb.cpu.CpuBus;
import org.jnesb.input.NesController;
import org.jnesb.ppu.Ppu2C02;

/**
 * Java port of the OneLoneCoder NES system bus (OLC-3 license).
 * Responsible for routing CPU/PPU memory access and advancing the system clock.
 */
public final class NesBus implements CpuBus {

    private final Cpu6502 cpu = new Cpu6502();
    private final Ppu2C02 ppu = new Ppu2C02();
    private final Apu apu = new Apu();
    private final int[] cpuRam = new int[2048];
    private final NesController[] controllers = {new NesController(), new NesController()};

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

    public Apu apu() {
        return apu;
    }

    public void insertCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
        ppu.connectCartridge(cartridge);
        cartridge.setMirrorConsumer(ppu::setMirrorMode);
    }
    public NesController controller(int index) {
        return controllers[index & 1];
    }

    public void reset() {
        cpu.reset();
        ppu.reset();
        apu.reset();
        for (NesController controller : controllers) {
            controller.reset();
        }
        systemClockCounter = 0;
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
            if (apu.pollIrq()) {
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
            return controllers[address - 0x4016].read() & 0x01;
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
}
