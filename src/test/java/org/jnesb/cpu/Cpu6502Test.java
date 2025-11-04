package org.jnesb.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class Cpu6502Test {

    private static final int FLAG_C = 1 << 0;
    private static final int FLAG_Z = 1 << 1;
    private static final int FLAG_V = 1 << 6;
    private static final int FLAG_N = 1 << 7;

    private static final int DEFAULT_START = 0x8000;

    private Cpu6502 cpu;
    private RamBus bus;

    @BeforeEach
    void setUp() {
        bus = new RamBus();
        cpu = new Cpu6502();
        cpu.connectBus(bus);
    }

    @Test
    void resetInitialisesRegistersAndProgramCounter() {
        resetCpu(DEFAULT_START);

        assertEquals(0x00, cpu.a);
        assertEquals(0x00, cpu.x);
        assertEquals(0x00, cpu.y);
        assertEquals(0xFD, cpu.stkp);
        assertEquals(DEFAULT_START, cpu.pc);
        assertTrue((cpu.status & (1 << 5)) != 0, "unused status flag should stay set");
    }

    @Test
    void ldaImmediateSetsZeroFlag() {
        writeProgram(DEFAULT_START, 0xA9, 0x00, 0x00); // LDA #$00; BRK

        resetCpu(DEFAULT_START);
        step(); // LDA

        assertEquals(0x00, cpu.a);
        assertTrue(flagSet(FLAG_Z));
        assertFalse(flagSet(FLAG_N));
    }

    @Test
    void adcSetsOverflowAndNegativeFlags() {
        writeProgram(DEFAULT_START, 0xA9, 0x50, 0x69, 0x50, 0x00); // LDA #$50; ADC #$50; BRK

        resetCpu(DEFAULT_START);
        step(); // LDA #$50
        step(); // ADC #$50

        assertEquals(0xA0, cpu.a);
        assertTrue(flagSet(FLAG_V), "overflow flag must be set for signed overflow");
        assertTrue(flagSet(FLAG_N), "negative flag must follow result bit 7");
        assertFalse(flagSet(FLAG_C), "carry should remain clear (no unsigned overflow)");
        assertFalse(flagSet(FLAG_Z));
    }

    @Test
    void branchCrossingPageConsumesExtraCycle() {
        int start = 0x80FC;
        // LDA #$01 (clear zero flag), BNE -128 -> 0x8080, BRK at branch target.
        writeProgram(start, 0xA9, 0x01, 0xD0, 0x80);
        bus.write(0x8080, 0x00);

        resetCpu(start);
        step(); // LDA
        int branchCycles = step(); // BNE

        assertEquals(4, branchCycles, "branch taken across page requires two extra cycles");
        assertEquals(0x8080, cpu.pc);
    }

    private void resetCpu(int startAddress) {
        setResetVector(startAddress);
        cpu.reset();
        drainCycles();
    }

    private void drainCycles() {
        while (!cpu.complete()) {
            cpu.clock();
        }
    }

    private int step() {
        int cycles = 0;
        do {
            cpu.clock();
            cycles++;
        } while (!cpu.complete());
        return cycles;
    }

    private void writeProgram(int address, int... bytes) {
        for (int i = 0; i < bytes.length; i++) {
            bus.write(address + i, bytes[i] & 0xFF);
        }
    }

    private void setResetVector(int address) {
        bus.write(0xFFFC, address & 0xFF);
        bus.write(0xFFFD, (address >> 8) & 0xFF);
    }

    private boolean flagSet(int mask) {
        return (cpu.status & mask) != 0;
    }

    private static final class RamBus implements CpuBus {
        private final int[] memory = new int[0x10000];

        @Override
        public int read(int address, boolean readOnly) {
            return memory[address & 0xFFFF];
        }

        @Override
        public void write(int address, int data) {
            memory[address & 0xFFFF] = data & 0xFF;
        }
    }
}
