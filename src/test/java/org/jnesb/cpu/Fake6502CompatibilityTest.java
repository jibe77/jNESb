package org.jnesb.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Port of a representative subset of the fake6502 test-suite.
 * <p>
 * The original tests live at
 * https://github.com/omarandlorraine/fake6502/blob/master/tests.c and target a
 * cycle-accurate NMOS 6502 implementation. We adapted the scenarios that apply
 * to the NES 2A03 CPU, focusing on observable behaviour (registers, flags,
 * memory effects). CMOS-only opcodes and decimal mode coverage are omitted
 * because the NES silicon does not implement them.
 */
final class Fake6502CompatibilityTest {

    private static final int FLAG_C = 1 << 0;
    private static final int FLAG_Z = 1 << 1;
    private static final int FLAG_I = 1 << 2;
    private static final int FLAG_D = 1 << 3;
    private static final int FLAG_B = 1 << 4;
    private static final int FLAG_U = 1 << 5;
    private static final int FLAG_V = 1 << 6;
    private static final int FLAG_N = 1 << 7;

    private Harness harness;

    @BeforeEach
    void setUp() {
        harness = new Harness();
    }

    @Test
    void zeroPageAddressingLoadsExpectedValue() {
        harness.setProgramCounter(0x0200);
        harness.poke(0x0003, 0x52);

        harness.execute(0xA5, 0x03); // LDA $03

        harness.assertRegisterA(0x52);
        harness.assertProgramCounter(0x0202);
        harness.assertFlag(FLAG_Z, false);
        harness.assertFlag(FLAG_N, false);
    }

    @Test
    void zeroPageIndexedWrapsWithinPage() {
        harness.setProgramCounter(0x0200);
        harness.setRegisterX(0x0F);
        harness.poke(0x0014, 0xAB); // (0x05 + 0x0F) & 0xFF == 0x14

        harness.execute(0xB5, 0x05); // LDA $05,X

        harness.assertRegisterA(0xAB);
        harness.assertProgramCounter(0x0202);
    }

    @Test
    void absoluteAddressingReadsFullAddress() {
        harness.setProgramCounter(0x0200);
        harness.poke(0x1234, 0x99);

        harness.execute(0xAD, 0x34, 0x12); // LDA $1234

        harness.assertRegisterA(0x99);
        harness.assertProgramCounter(0x0203);
    }

    @Test
    void absoluteIndexedCrossingPageAddsCycles() {
        harness.setProgramCounter(0x0200);
        harness.setRegisterX(0x30);
        harness.poke(0x12F0 + 0x30, 0x55); // $12F0 + X crosses into $13xx

        ExecutionResult result = harness.execute(0xBD, 0xF0, 0x12); // LDA $12F0,X

        harness.assertRegisterA(0x55);
        harness.assertProgramCounter(0x0203);
        assertTrue(result.cycles() >= 5, "page crossing should incur extra cycle");
    }

    @Test
    void indirectYAddressingAddsOffsetAndSetsFlags() {
        harness.setProgramCounter(0x0200);
        harness.setRegisterY(0x04);
        harness.poke(0x00F0, 0x00);
        harness.poke(0x00F1, 0x80);
        harness.poke(0x8004, 0x7F);

        harness.execute(0xB1, 0xF0); // LDA ($F0),Y

        harness.assertRegisterA(0x7F);
        harness.assertFlag(FLAG_N, false);
        harness.assertFlag(FLAG_Z, false);
    }

    @Test
    void branchForwardAndBackwardWorks() {
        harness.setProgramCounter(0x0200);

        // Force zero flag = 0 by loading a non-zero value
        harness.execute(0xA9, 0x01); // LDA #$01
        harness.assertFlag(FLAG_Z, false);

        harness.execute(0xD0, 0x02); // BNE +2 (to 0x0206)
        harness.assertProgramCounter(0x0206);

        // Backward branch by -4 (0xFC) -> 0x0204
        harness.execute(0xD0, 0xFC);
        harness.assertProgramCounter(0x0204);
    }

    @Test
    void comparisonsUpdateFlagsCorrectly() {
        harness.setProgramCounter(0x0200);
        harness.setRegisterA(0x40);
        harness.execute(0xC9, 0x20); // CMP #$20
        harness.assertFlag(FLAG_C, true);
        harness.assertFlag(FLAG_Z, false);
        harness.assertFlag(FLAG_N, false);

        harness.execute(0xC9, 0x40);
        harness.assertFlag(FLAG_Z, true);

        harness.execute(0xC9, 0x80);
        harness.assertFlag(FLAG_C, false);
        harness.assertFlag(FLAG_N, true);
    }

    @Test
    void incrementAndDecrementAffectMemoryAndFlags() {
        harness.setProgramCounter(0x0200);
        harness.poke(0x00FE, 0xFF);
        harness.execute(0xE6, 0xFE); // INC $FE -> 0x00
        harness.assertMemory(0x00FE, 0x00);
        harness.assertFlag(FLAG_Z, true);

        harness.execute(0xC6, 0xFE); // DEC $FE -> 0xFF
        harness.assertMemory(0x00FE, 0xFF);
        harness.assertFlag(FLAG_N, true);
    }

    @Test
    void loadInstructionsSetFlags() {
        harness.setProgramCounter(0x0200);
        harness.execute(0xA9, 0x00); // LDA #$00
        harness.assertRegisterA(0x00);
        harness.assertFlag(FLAG_Z, true);

        harness.execute(0xA2, 0xFF); // LDX #$FF
        harness.assertRegisterX(0xFF);
        harness.assertFlag(FLAG_N, true);

        harness.execute(0xA0, 0x7F); // LDY #$7F
        harness.assertRegisterY(0x7F);
        harness.assertFlag(FLAG_N, false);
    }

    @Test
    void transfersCopyRegisterValues() {
        harness.setProgramCounter(0x0200);
        harness.setRegisterA(0x34);
        harness.execute(0xAA); // TAX
        harness.assertRegisterX(0x34);
        harness.assertFlag(FLAG_Z, false);

        harness.execute(0xA8); // TAY
        harness.assertRegisterY(0x34);

        harness.execute(0x8A); // TXA
        harness.assertRegisterA(0x34);

        harness.execute(0x98); // TYA
        harness.assertRegisterA(0x34);
    }

    @Test
    void logicalAndShiftOperationsMatchExpectations() {
        harness.setProgramCounter(0x0200);
        harness.setRegisterA(0xF0);
        harness.execute(0x29, 0x0F); // AND #$0F -> 0x00
        harness.assertRegisterA(0x00);
        harness.assertFlag(FLAG_Z, true);

        harness.setRegisterA(0x80);
        harness.execute(0x0A); // ASL A -> 0x00 with carry
        harness.assertRegisterA(0x00);
        harness.assertFlag(FLAG_C, true);
        harness.assertFlag(FLAG_Z, true);

        harness.execute(0x18); // CLC ensure carry cleared before rotation through carry
        harness.setRegisterA(0x01);
        harness.execute(0x2A); // ROL A -> 0x02
        harness.assertRegisterA(0x02);
        harness.assertFlag(FLAG_C, false);
    }

    @Test
    void bitInstructionExaminesBitsWithoutChangingAccumulator() {
        harness.setProgramCounter(0x0200);
        harness.setRegisterA(0xFF);
        harness.poke(0x0042, 0x40);

        harness.execute(0x2C, 0x42, 0x00); // BIT $0042

        harness.assertRegisterA(0xFF);
        harness.assertFlag(FLAG_V, true);
        harness.assertFlag(FLAG_N, false);
        harness.assertFlag(FLAG_Z, false);
    }

    @Test
    void storeInstructionsWriteToMemory() {
        harness.setProgramCounter(0x0200);
        harness.setRegisterA(0x12);
        harness.setRegisterX(0x34);
        harness.setRegisterY(0x56);

        harness.execute(0x8D, 0x00, 0x80); // STA $8000
        harness.assertMemory(0x8000, 0x12);

        harness.execute(0x86, 0x10); // STX $10
        harness.assertMemory(0x0010, 0x34);

        harness.execute(0x8C, 0x34, 0x12); // STY $1234
        harness.assertMemory(0x1234, 0x56);
    }

    @Test
    void jsrAndRtsRoundTripProgramCounter() {
        harness.setProgramCounter(0x0300);
        harness.poke(0x1234, 0xEA); // target NOP

        harness.execute(0x20, 0x34, 0x12); // JSR $1234
        harness.assertProgramCounter(0x1234);
        harness.assertStackPointer(0xFB);

        harness.execute(0x60); // RTS
        harness.assertProgramCounter(0x0303);
    }

    @Test
    void brkPushesCorrectContextAndRtiRestoresIt() {
        harness.setProgramCounter(0x0400);
        harness.poke(0xFFFE, 0x00);
        harness.poke(0xFFFF, 0x90);
        harness.execute(0x00); // BRK

        harness.assertProgramCounter(0x9000);
        harness.assertStackPointer(0xFA);

        // Emulate ISR writing return address/flags
        harness.poke(0x01FB, 0x12); // P
        harness.poke(0x01FC, 0x05); // PCL
        harness.poke(0x01FD, 0x03); // PCH
        harness.setStackPointer(0xFA);

        harness.execute(0x40); // RTI
        harness.assertProgramCounter(0x0305);
        harness.assertStatus((0x12 & ~FLAG_B) | FLAG_U);
    }

    @Test
    void eorOraOperateBitwise() {
        harness.setProgramCounter(0x0200);
        harness.setRegisterA(0xAA);

        harness.execute(0x49, 0xFF); // EOR #$FF -> 0x55
        harness.assertRegisterA(0x55);

        harness.execute(0x09, 0x0F); // ORA #$0F -> 0x5F
        harness.assertRegisterA(0x5F);
        harness.assertFlag(FLAG_Z, false);
        harness.assertFlag(FLAG_N, false);
    }

    private static final class Harness {
        private final Cpu6502 cpu = new Cpu6502();
        private final CountingBus bus = new CountingBus();

        Harness() {
            cpu.connectBus(bus);
            // Default reset vector points to 0x8000, make sure it is initialised.
            bus.poke(0xFFFC, 0x00);
            bus.poke(0xFFFD, 0x80);
            cpu.reset();
            tickUntilComplete();
        }

        ExecutionResult execute(int... bytes) {
            int pc = cpu.pc & 0xFFFF;
            for (int i = 0; i < bytes.length; i++) {
                bus.poke((pc + i) & 0xFFFF, bytes[i]);
            }
            bus.resetCounts();

            int cycles = 0;
            do {
                cpu.clock();
                cycles++;
            } while (!cpu.complete());

            return new ExecutionResult(cycles, bus.reads, bus.writes);
        }

        void poke(int address, int value) {
            bus.poke(address, value);
        }

        void assertMemory(int address, int expected) {
            assertEquals(expected & 0xFF, bus.peek(address), "memory @" + hex(address));
        }

        void assertProgramCounter(int expected) {
            assertEquals(expected & 0xFFFF, cpu.pc & 0xFFFF, "PC");
        }

        void assertRegisterA(int expected) {
            assertEquals(expected & 0xFF, cpu.a & 0xFF, "A");
        }

        void assertRegisterX(int expected) {
            assertEquals(expected & 0xFF, cpu.x & 0xFF, "X");
        }

        void assertRegisterY(int expected) {
            assertEquals(expected & 0xFF, cpu.y & 0xFF, "Y");
        }

        void assertStackPointer(int expected) {
            assertEquals(expected & 0xFF, cpu.stkp & 0xFF, "SP");
        }

        void assertStatus(int expected) {
            assertEquals(expected & 0xFF, cpu.status & 0xFF, "status");
        }

        void assertFlag(int flag, boolean expectedSet) {
            boolean actual = (cpu.status & flag) != 0;
            if (flag == FLAG_U) {
                // Unused bit is always kept set by the implementation.
                assertTrue(actual, "unused flag should stay set");
            } else if (expectedSet) {
                assertTrue(actual, "expected flag " + flagName(flag) + " to be set");
            } else {
                assertFalse(actual, "expected flag " + flagName(flag) + " to be clear");
            }
        }

        void setProgramCounter(int value) {
            cpu.pc = value & 0xFFFF;
        }

        void setRegisterA(int value) {
            cpu.a = value & 0xFF;
        }

        void setRegisterX(int value) {
            cpu.x = value & 0xFF;
        }

        void setRegisterY(int value) {
            cpu.y = value & 0xFF;
        }

        void setStackPointer(int value) {
            cpu.stkp = value & 0xFF;
        }

        private void tickUntilComplete() {
            while (!cpu.complete()) {
                cpu.clock();
            }
        }

        private static String flagName(int flag) {
            return switch (flag) {
                case FLAG_C -> "C";
                case FLAG_Z -> "Z";
                case FLAG_I -> "I";
                case FLAG_D -> "D";
                case FLAG_B -> "B";
                case FLAG_U -> "U";
                case FLAG_V -> "V";
                case FLAG_N -> "N";
                default -> "unknown";
            };
        }

        private static String hex(int value) {
            return String.format("$%04X", value & 0xFFFF);
        }
    }

    private static final class CountingBus implements CpuBus {
        private final int[] memory = new int[0x10000];
        private int reads;
        private int writes;

        @Override
        public int read(int address, boolean readOnly) {
            reads++;
            return memory[address & 0xFFFF];
        }

        @Override
        public void write(int address, int data) {
            writes++;
            memory[address & 0xFFFF] = data & 0xFF;
        }

        void resetCounts() {
            reads = 0;
            writes = 0;
        }

        void poke(int address, int value) {
            memory[address & 0xFFFF] = value & 0xFF;
        }

        int peek(int address) {
            return memory[address & 0xFFFF];
        }
    }

    private record ExecutionResult(int cycles, int reads, int writes) {
    }
}
