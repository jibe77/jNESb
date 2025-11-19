package org.jnesb.cpu;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jnesb.state.Stateful;

/**
 * Java port of the javidx9 olc6502 CPU implementation.
 * Original source: https://github.com/OneLoneCoder/olcNES (OLC-3 license)
 */
public class Cpu6502 implements Stateful {

    // State size: 6 registers (1 byte each except PC which is 2) + internal state
    private static final int STATE_SIZE = 32;

    // Status flag bit positions
    private static final int FLAG_C = 1 << 0; // Carry
    private static final int FLAG_Z = 1 << 1; // Zero
    private static final int FLAG_I = 1 << 2; // Disable Interrupts
    private static final int FLAG_D = 1 << 3; // Decimal Mode (unused by NES)
    private static final int FLAG_B = 1 << 4; // Break
    private static final int FLAG_U = 1 << 5; // Unused (always set)
    private static final int FLAG_V = 1 << 6; // Overflow
    private static final int FLAG_N = 1 << 7; // Negative

    private final Instruction[] lookup = new Instruction[256];

    private CpuBus bus;

    // CPU registers (8-bit except PC)
    public int a = 0x00;
    public int x = 0x00;
    public int y = 0x00;
    public int stkp = 0x00;
    public int pc = 0x0000;
    public int status = 0x00;

    // Internal helpers
    private int fetched = 0x00;
    private int temp = 0x0000;
    private int addrAbs = 0x0000;
    private int addrRel = 0x0000;
    private int opcode = 0x00;
    private int cycles = 0;
    private long clockCount = 0L;

    public Cpu6502() {
        buildLookup();
    }

    public void connectBus(CpuBus newBus) {
        bus = Objects.requireNonNull(newBus, "bus");
    }

    public void reset() {
        addrAbs = 0xFFFC;
        int lo = read(addrAbs);
        int hi = read(addrAbs + 1);

        pc = ((hi << 8) | lo) & 0xFFFF;

        a = 0;
        x = 0;
        y = 0;
        stkp = 0xFD;
        status = FLAG_U;

        addrRel = 0;
        addrAbs = 0;
        fetched = 0;

        cycles = 8;
    }

    public void irq() {
        if (getFlag(FLAG_I) == 0) {
            write(0x0100 + stkp, (pc >> 8) & 0x00FF);
            stkp = (stkp - 1) & 0xFF;
            write(0x0100 + stkp, pc & 0x00FF);
            stkp = (stkp - 1) & 0xFF;

            setFlag(FLAG_B, false);
            setFlag(FLAG_U, true);
            setFlag(FLAG_I, true);
            write(0x0100 + stkp, status);
            stkp = (stkp - 1) & 0xFF;

            addrAbs = 0xFFFE;
            int lo = read(addrAbs);
            int hi = read(addrAbs + 1);
            pc = ((hi << 8) | lo) & 0xFFFF;

            cycles = 7;
        }
    }

    public void nmi() {
        write(0x0100 + stkp, (pc >> 8) & 0x00FF);
        stkp = (stkp - 1) & 0xFF;
        write(0x0100 + stkp, pc & 0x00FF);
        stkp = (stkp - 1) & 0xFF;

        setFlag(FLAG_B, false);
        setFlag(FLAG_U, true);
        setFlag(FLAG_I, true);
        write(0x0100 + stkp, status);
        stkp = (stkp - 1) & 0xFF;

        addrAbs = 0xFFFA;
        int lo = read(addrAbs);
        int hi = read(addrAbs + 1);
        pc = ((hi << 8) | lo) & 0xFFFF;

        cycles = 8;
    }

    public void clock() {
        if (cycles == 0) {
            opcode = read(pc);
            opcode &= 0xFF;

            setFlag(FLAG_U, true);

            pc = (pc + 1) & 0xFFFF;

            Instruction instruction = lookup[opcode];
            cycles = instruction.cycles;

            int additionalCycle1 = instruction.addrmode.apply();
            int additionalCycle2 = instruction.operate.apply();

            cycles += (additionalCycle1 & additionalCycle2);
            setFlag(FLAG_U, true);
        }

        clockCount++;
        cycles = Math.max(cycles - 1, 0);
    }

    public boolean complete() {
        return cycles == 0;
    }

    long getClockCount() {
        return clockCount;
    }

    public void stall(int additionalCycles) {
        if (additionalCycles > 0) {
            cycles += additionalCycles;
        }
    }

    @Override
    public byte[] saveState() {
        ByteBuffer buffer = ByteBuffer.allocate(STATE_SIZE);
        // Registers
        buffer.put((byte) (a & 0xFF));
        buffer.put((byte) (x & 0xFF));
        buffer.put((byte) (y & 0xFF));
        buffer.put((byte) (stkp & 0xFF));
        buffer.putShort((short) (pc & 0xFFFF));
        buffer.put((byte) (status & 0xFF));
        // Internal state
        buffer.put((byte) (fetched & 0xFF));
        buffer.putShort((short) (temp & 0xFFFF));
        buffer.putShort((short) (addrAbs & 0xFFFF));
        buffer.putShort((short) (addrRel & 0xFFFF));
        buffer.put((byte) (opcode & 0xFF));
        buffer.put((byte) (cycles & 0xFF));
        buffer.putLong(clockCount);
        return buffer.array();
    }

    @Override
    public void loadState(byte[] data) {
        if (data == null || data.length < STATE_SIZE) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        // Registers
        a = buffer.get() & 0xFF;
        x = buffer.get() & 0xFF;
        y = buffer.get() & 0xFF;
        stkp = buffer.get() & 0xFF;
        pc = buffer.getShort() & 0xFFFF;
        status = buffer.get() & 0xFF;
        // Internal state
        fetched = buffer.get() & 0xFF;
        temp = buffer.getShort() & 0xFFFF;
        addrAbs = buffer.getShort() & 0xFFFF;
        addrRel = buffer.getShort() & 0xFFFF;
        opcode = buffer.get() & 0xFF;
        cycles = buffer.get() & 0xFF;
        clockCount = buffer.getLong();
    }

    @Override
    public int stateSize() {
        return STATE_SIZE;
    }

    public Map<Integer, String> disassemble(int start, int stop) {
        Map<Integer, String> result = new LinkedHashMap<>();
        int addr = start & 0xFFFF;

        while (addr <= (stop & 0xFFFF)) {
            int lineAddr = addr;
            StringBuilder inst = new StringBuilder();
            inst.append("$").append(hex(addr, 4)).append(": ");

            int op = read(addr, true);
            addr = (addr + 1) & 0xFFFF;
            Instruction instruction = lookup[op & 0xFF];
            inst.append(instruction.name).append(" ");

            switch (instruction.addrmodeType) {
                case IMP:
                    inst.append("{IMP}");
                    break;
                case IMM: {
                    int value = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    inst.append("#$").append(hex(value, 2)).append(" {IMM}");
                    break;
                }
                case ZP0: {
                    int lo = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    inst.append("$").append(hex(lo, 2)).append(" {ZP0}");
                    break;
                }
                case ZPX: {
                    int lo = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    inst.append("$").append(hex(lo, 2)).append(", X {ZPX}");
                    break;
                }
                case ZPY: {
                    int lo = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    inst.append("$").append(hex(lo, 2)).append(", Y {ZPY}");
                    break;
                }
                case IZX: {
                    int lo = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    inst.append("($").append(hex(lo, 2)).append(", X) {IZX}");
                    break;
                }
                case IZY: {
                    int lo = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    inst.append("($").append(hex(lo, 2)).append("), Y {IZY}");
                    break;
                }
                case ABS: {
                    int lo = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    int hi = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    inst.append("$").append(hex((hi << 8) | lo, 4)).append(" {ABS}");
                    break;
                }
                case ABX: {
                    int lo = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    int hi = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    inst.append("$").append(hex((hi << 8) | lo, 4)).append(", X {ABX}");
                    break;
                }
                case ABY: {
                    int lo = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    int hi = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    inst.append("$").append(hex((hi << 8) | lo, 4)).append(", Y {ABY}");
                    break;
                }
                case IND: {
                    int lo = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    int hi = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    inst.append("($").append(hex((hi << 8) | lo, 4)).append(") {IND}");
                    break;
                }
                case REL: {
                    int value = read(addr, true);
                    addr = (addr + 1) & 0xFFFF;
                    int target = (addr + (byte) value) & 0xFFFF;
                    inst.append("$").append(hex(value, 2))
                            .append(" [$").append(hex(target, 4)).append("] {REL}");
                    break;
                }
                default:
                    throw new IllegalStateException("Unhandled addressing mode");
            }

            result.put(lineAddr, inst.toString());
        }

        return result;
    }

    private void buildLookup() {
        for (int i = 0; i < lookup.length; i++) {
            lookup[i] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        }

        lookup[0x00] = instruction("BRK", this::BRK, this::IMM, AddressingModeType.IMM, false, 7);
        lookup[0x01] = instruction("ORA", this::ORA, this::IZX, AddressingModeType.IZX, false, 6);
        lookup[0x02] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x03] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 8);
        lookup[0x04] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 3);
        lookup[0x05] = instruction("ORA", this::ORA, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0x06] = instruction("ASL", this::ASL, this::ZP0, AddressingModeType.ZP0, false, 5);
        lookup[0x07] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 5);
        lookup[0x08] = instruction("PHP", this::PHP, this::IMP, AddressingModeType.IMP, true, 3);
        lookup[0x09] = instruction("ORA", this::ORA, this::IMM, AddressingModeType.IMM, false, 2);
        lookup[0x0A] = instruction("ASL", this::ASL, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x0B] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x0C] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x0D] = instruction("ORA", this::ORA, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0x0E] = instruction("ASL", this::ASL, this::ABS, AddressingModeType.ABS, false, 6);
        lookup[0x0F] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0x10] = instruction("BPL", this::BPL, this::REL, AddressingModeType.REL, false, 2);
        lookup[0x11] = instruction("ORA", this::ORA, this::IZY, AddressingModeType.IZY, false, 5);
        lookup[0x12] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x13] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 8);
        lookup[0x14] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x15] = instruction("ORA", this::ORA, this::ZPX, AddressingModeType.ZPX, false, 4);
        lookup[0x16] = instruction("ASL", this::ASL, this::ZPX, AddressingModeType.ZPX, false, 6);
        lookup[0x17] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0x18] = instruction("CLC", this::CLC, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x19] = instruction("ORA", this::ORA, this::ABY, AddressingModeType.ABY, false, 4);
        lookup[0x1A] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x1B] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 7);
        lookup[0x1C] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x1D] = instruction("ORA", this::ORA, this::ABX, AddressingModeType.ABX, false, 4);
        lookup[0x1E] = instruction("ASL", this::ASL, this::ABX, AddressingModeType.ABX, false, 7);
        lookup[0x1F] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 7);
        lookup[0x20] = instruction("JSR", this::JSR, this::ABS, AddressingModeType.ABS, false, 6);
        lookup[0x21] = instruction("AND", this::AND, this::IZX, AddressingModeType.IZX, false, 6);
        lookup[0x22] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x23] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 8);
        lookup[0x24] = instruction("BIT", this::BIT, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0x25] = instruction("AND", this::AND, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0x26] = instruction("ROL", this::ROL, this::ZP0, AddressingModeType.ZP0, false, 5);
        lookup[0x27] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 5);
        lookup[0x28] = instruction("PLP", this::PLP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x29] = instruction("AND", this::AND, this::IMM, AddressingModeType.IMM, false, 2);
        lookup[0x2A] = instruction("ROL", this::ROL, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x2B] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x2C] = instruction("BIT", this::BIT, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0x2D] = instruction("AND", this::AND, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0x2E] = instruction("ROL", this::ROL, this::ABS, AddressingModeType.ABS, false, 6);
        lookup[0x2F] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0x30] = instruction("BMI", this::BMI, this::REL, AddressingModeType.REL, false, 2);
        lookup[0x31] = instruction("AND", this::AND, this::IZY, AddressingModeType.IZY, false, 5);
        lookup[0x32] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x33] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 8);
        lookup[0x34] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x35] = instruction("AND", this::AND, this::ZPX, AddressingModeType.ZPX, false, 4);
        lookup[0x36] = instruction("ROL", this::ROL, this::ZPX, AddressingModeType.ZPX, false, 6);
        lookup[0x37] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0x38] = instruction("SEC", this::SEC, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x39] = instruction("AND", this::AND, this::ABY, AddressingModeType.ABY, false, 4);
        lookup[0x3A] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x3B] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 7);
        lookup[0x3C] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x3D] = instruction("AND", this::AND, this::ABX, AddressingModeType.ABX, false, 4);
        lookup[0x3E] = instruction("ROL", this::ROL, this::ABX, AddressingModeType.ABX, false, 7);
        lookup[0x3F] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 7);
        lookup[0x40] = instruction("RTI", this::RTI, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0x41] = instruction("EOR", this::EOR, this::IZX, AddressingModeType.IZX, false, 6);
        lookup[0x42] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x43] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 8);
        lookup[0x44] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 3);
        lookup[0x45] = instruction("EOR", this::EOR, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0x46] = instruction("LSR", this::LSR, this::ZP0, AddressingModeType.ZP0, false, 5);
        lookup[0x47] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 5);
        lookup[0x48] = instruction("PHA", this::PHA, this::IMP, AddressingModeType.IMP, true, 3);
        lookup[0x49] = instruction("EOR", this::EOR, this::IMM, AddressingModeType.IMM, false, 2);
        lookup[0x4A] = instruction("LSR", this::LSR, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x4B] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x4C] = instruction("JMP", this::JMP, this::ABS, AddressingModeType.ABS, false, 3);
        lookup[0x4D] = instruction("EOR", this::EOR, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0x4E] = instruction("LSR", this::LSR, this::ABS, AddressingModeType.ABS, false, 6);
        lookup[0x4F] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0x50] = instruction("BVC", this::BVC, this::REL, AddressingModeType.REL, false, 2);
        lookup[0x51] = instruction("EOR", this::EOR, this::IZY, AddressingModeType.IZY, false, 5);
        lookup[0x52] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x53] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 8);
        lookup[0x54] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x55] = instruction("EOR", this::EOR, this::ZPX, AddressingModeType.ZPX, false, 4);
        lookup[0x56] = instruction("LSR", this::LSR, this::ZPX, AddressingModeType.ZPX, false, 6);
        lookup[0x57] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0x58] = instruction("CLI", this::CLI, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x59] = instruction("EOR", this::EOR, this::ABY, AddressingModeType.ABY, false, 4);
        lookup[0x5A] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x5B] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 7);
        lookup[0x5C] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x5D] = instruction("EOR", this::EOR, this::ABX, AddressingModeType.ABX, false, 4);
        lookup[0x5E] = instruction("LSR", this::LSR, this::ABX, AddressingModeType.ABX, false, 7);
        lookup[0x5F] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 7);
        lookup[0x60] = instruction("RTS", this::RTS, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0x61] = instruction("ADC", this::ADC, this::IZX, AddressingModeType.IZX, false, 6);
        lookup[0x62] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x63] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 8);
        lookup[0x64] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 3);
        lookup[0x65] = instruction("ADC", this::ADC, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0x66] = instruction("ROR", this::ROR, this::ZP0, AddressingModeType.ZP0, false, 5);
        lookup[0x67] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 5);
        lookup[0x68] = instruction("PLA", this::PLA, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x69] = instruction("ADC", this::ADC, this::IMM, AddressingModeType.IMM, false, 2);
        lookup[0x6A] = instruction("ROR", this::ROR, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x6B] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x6C] = instruction("JMP", this::JMP, this::IND, AddressingModeType.IND, false, 5);
        lookup[0x6D] = instruction("ADC", this::ADC, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0x6E] = instruction("ROR", this::ROR, this::ABS, AddressingModeType.ABS, false, 6);
        lookup[0x6F] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0x70] = instruction("BVS", this::BVS, this::REL, AddressingModeType.REL, false, 2);
        lookup[0x71] = instruction("ADC", this::ADC, this::IZY, AddressingModeType.IZY, false, 5);
        lookup[0x72] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x73] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 8);
        lookup[0x74] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x75] = instruction("ADC", this::ADC, this::ZPX, AddressingModeType.ZPX, false, 4);
        lookup[0x76] = instruction("ROR", this::ROR, this::ZPX, AddressingModeType.ZPX, false, 6);
        lookup[0x77] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0x78] = instruction("SEI", this::SEI, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x79] = instruction("ADC", this::ADC, this::ABY, AddressingModeType.ABY, false, 4);
        lookup[0x7A] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x7B] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 7);
        lookup[0x7C] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x7D] = instruction("ADC", this::ADC, this::ABX, AddressingModeType.ABX, false, 4);
        lookup[0x7E] = instruction("ROR", this::ROR, this::ABX, AddressingModeType.ABX, false, 7);
        lookup[0x7F] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 7);
        lookup[0x80] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x81] = instruction("STA", this::STA, this::IZX, AddressingModeType.IZX, false, 6);
        lookup[0x82] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x83] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0x84] = instruction("STY", this::STY, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0x85] = instruction("STA", this::STA, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0x86] = instruction("STX", this::STX, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0x87] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 3);
        lookup[0x88] = instruction("DEY", this::DEY, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x89] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x8A] = instruction("TXA", this::TXA, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x8B] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x8C] = instruction("STY", this::STY, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0x8D] = instruction("STA", this::STA, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0x8E] = instruction("STX", this::STX, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0x8F] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x90] = instruction("BCC", this::BCC, this::REL, AddressingModeType.REL, false, 2);
        lookup[0x91] = instruction("STA", this::STA, this::IZY, AddressingModeType.IZY, false, 6);
        lookup[0x92] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x93] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0x94] = instruction("STY", this::STY, this::ZPX, AddressingModeType.ZPX, false, 4);
        lookup[0x95] = instruction("STA", this::STA, this::ZPX, AddressingModeType.ZPX, false, 4);
        lookup[0x96] = instruction("STX", this::STX, this::ZPY, AddressingModeType.ZPY, false, 4);
        lookup[0x97] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0x98] = instruction("TYA", this::TYA, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x99] = instruction("STA", this::STA, this::ABY, AddressingModeType.ABY, false, 5);
        lookup[0x9A] = instruction("TXS", this::TXS, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0x9B] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 5);
        lookup[0x9C] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 5);
        lookup[0x9D] = instruction("STA", this::STA, this::ABX, AddressingModeType.ABX, false, 5);
        lookup[0x9E] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 5);
        lookup[0x9F] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 5);
        lookup[0xA0] = instruction("LDY", this::LDY, this::IMM, AddressingModeType.IMM, false, 2);
        lookup[0xA1] = instruction("LDA", this::LDA, this::IZX, AddressingModeType.IZX, false, 6);
        lookup[0xA2] = instruction("LDX", this::LDX, this::IMM, AddressingModeType.IMM, false, 2);
        lookup[0xA3] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0xA4] = instruction("LDY", this::LDY, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0xA5] = instruction("LDA", this::LDA, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0xA6] = instruction("LDX", this::LDX, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0xA7] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 3);
        lookup[0xA8] = instruction("TAY", this::TAY, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xA9] = instruction("LDA", this::LDA, this::IMM, AddressingModeType.IMM, false, 2);
        lookup[0xAA] = instruction("TAX", this::TAX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xAB] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xAC] = instruction("LDY", this::LDY, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0xAD] = instruction("LDA", this::LDA, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0xAE] = instruction("LDX", this::LDX, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0xAF] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0xB0] = instruction("BCS", this::BCS, this::REL, AddressingModeType.REL, false, 2);
        lookup[0xB1] = instruction("LDA", this::LDA, this::IZY, AddressingModeType.IZY, false, 5);
        lookup[0xB2] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xB3] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 5);
        lookup[0xB4] = instruction("LDY", this::LDY, this::ZPX, AddressingModeType.ZPX, false, 4);
        lookup[0xB5] = instruction("LDA", this::LDA, this::ZPX, AddressingModeType.ZPX, false, 4);
        lookup[0xB6] = instruction("LDX", this::LDX, this::ZPY, AddressingModeType.ZPY, false, 4);
        lookup[0xB7] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0xB8] = instruction("CLV", this::CLV, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xB9] = instruction("LDA", this::LDA, this::ABY, AddressingModeType.ABY, false, 4);
        lookup[0xBA] = instruction("TSX", this::TSX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xBB] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0xBC] = instruction("LDY", this::LDY, this::ABX, AddressingModeType.ABX, false, 4);
        lookup[0xBD] = instruction("LDA", this::LDA, this::ABX, AddressingModeType.ABX, false, 4);
        lookup[0xBE] = instruction("LDX", this::LDX, this::ABY, AddressingModeType.ABY, false, 4);
        lookup[0xBF] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0xC0] = instruction("CPY", this::CPY, this::IMM, AddressingModeType.IMM, false, 2);
        lookup[0xC1] = instruction("CMP", this::CMP, this::IZX, AddressingModeType.IZX, false, 6);
        lookup[0xC2] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xC3] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 8);
        lookup[0xC4] = instruction("CPY", this::CPY, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0xC5] = instruction("CMP", this::CMP, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0xC6] = instruction("DEC", this::DEC, this::ZP0, AddressingModeType.ZP0, false, 5);
        lookup[0xC7] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 5);
        lookup[0xC8] = instruction("INY", this::INY, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xC9] = instruction("CMP", this::CMP, this::IMM, AddressingModeType.IMM, false, 2);
        lookup[0xCA] = instruction("DEX", this::DEX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xCB] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xCC] = instruction("CPY", this::CPY, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0xCD] = instruction("CMP", this::CMP, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0xCE] = instruction("DEC", this::DEC, this::ABS, AddressingModeType.ABS, false, 6);
        lookup[0xCF] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0xD0] = instruction("BNE", this::BNE, this::REL, AddressingModeType.REL, false, 2);
        lookup[0xD1] = instruction("CMP", this::CMP, this::IZY, AddressingModeType.IZY, false, 5);
        lookup[0xD2] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xD3] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 8);
        lookup[0xD4] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0xD5] = instruction("CMP", this::CMP, this::ZPX, AddressingModeType.ZPX, false, 4);
        lookup[0xD6] = instruction("DEC", this::DEC, this::ZPX, AddressingModeType.ZPX, false, 6);
        lookup[0xD7] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0xD8] = instruction("CLD", this::CLD, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xD9] = instruction("CMP", this::CMP, this::ABY, AddressingModeType.ABY, false, 4);
        lookup[0xDA] = instruction("NOP", this::NOP, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xDB] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 7);
        lookup[0xDC] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0xDD] = instruction("CMP", this::CMP, this::ABX, AddressingModeType.ABX, false, 4);
        lookup[0xDE] = instruction("DEC", this::DEC, this::ABX, AddressingModeType.ABX, false, 7);
        lookup[0xDF] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 7);
        lookup[0xE0] = instruction("CPX", this::CPX, this::IMM, AddressingModeType.IMM, false, 2);
        lookup[0xE1] = instruction("SBC", this::SBC, this::IZX, AddressingModeType.IZX, false, 6);
        lookup[0xE2] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xE3] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 8);
        lookup[0xE4] = instruction("CPX", this::CPX, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0xE5] = instruction("SBC", this::SBC, this::ZP0, AddressingModeType.ZP0, false, 3);
        lookup[0xE6] = instruction("INC", this::INC, this::ZP0, AddressingModeType.ZP0, false, 5);
        lookup[0xE7] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 5);
        lookup[0xE8] = instruction("INX", this::INX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xE9] = instruction("SBC", this::SBC, this::IMM, AddressingModeType.IMM, false, 2);
        lookup[0xEA] = instruction("NOP", this::NOP, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xEB] = instruction("???", this::SBC, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xEC] = instruction("CPX", this::CPX, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0xED] = instruction("SBC", this::SBC, this::ABS, AddressingModeType.ABS, false, 4);
        lookup[0xEE] = instruction("INC", this::INC, this::ABS, AddressingModeType.ABS, false, 6);
        lookup[0xEF] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0xF0] = instruction("BEQ", this::BEQ, this::REL, AddressingModeType.REL, false, 2);
        lookup[0xF1] = instruction("SBC", this::SBC, this::IZY, AddressingModeType.IZY, false, 5);
        lookup[0xF2] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xF3] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 8);
        lookup[0xF4] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0xF5] = instruction("SBC", this::SBC, this::ZPX, AddressingModeType.ZPX, false, 4);
        lookup[0xF6] = instruction("INC", this::INC, this::ZPX, AddressingModeType.ZPX, false, 6);
        lookup[0xF7] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 6);
        lookup[0xF8] = instruction("SED", this::SED, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xF9] = instruction("SBC", this::SBC, this::ABY, AddressingModeType.ABY, false, 4);
        lookup[0xFA] = instruction("NOP", this::NOP, this::IMP, AddressingModeType.IMP, true, 2);
        lookup[0xFB] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 7);
        lookup[0xFC] = instruction("???", this::NOP, this::IMP, AddressingModeType.IMP, true, 4);
        lookup[0xFD] = instruction("SBC", this::SBC, this::ABX, AddressingModeType.ABX, false, 4);
        lookup[0xFE] = instruction("INC", this::INC, this::ABX, AddressingModeType.ABX, false, 7);
        lookup[0xFF] = instruction("???", this::XXX, this::IMP, AddressingModeType.IMP, true, 7);
    }


    private Instruction instruction(String name, Operation op, AddressingMode mode,
                                    AddressingModeType type, boolean implied, int cycles) {
        return new Instruction(name, op, mode, type, implied, cycles);
    }

    private int getFlag(int flag) {
        return (status & flag) != 0 ? 1 : 0;
    }

    private void setFlag(int flag, boolean value) {
        if (value) {
            status |= flag;
        } else {
            status &= ~flag;
        }
        status &= 0xFF;
    }

    private int read(int address) {
        return read(address, false);
    }

    private int read(int address, boolean readOnly) {
        if (bus == null) {
            return 0x00;
        }
        return bus.read(address & 0xFFFF, readOnly) & 0xFF;
    }

    private void write(int address, int data) {
        if (bus == null) {
            return;
        }
        bus.write(address & 0xFFFF, data & 0xFF);
    }

    private String hex(int value, int digits) {
        StringBuilder sb = new StringBuilder(digits);
        for (int i = digits - 1; i >= 0; i--) {
            sb.insert(0, "0123456789ABCDEF".charAt(value & 0xF));
            value >>>= 4;
        }
        return sb.toString();
    }

    // Addressing modes -------------------------------------------------------

    private int IMP() {
        fetched = a & 0xFF;
        return 0;
    }

    private int IMM() {
        addrAbs = pc & 0xFFFF;
        pc = (pc + 1) & 0xFFFF;
        return 0;
    }

    private int ZP0() {
        addrAbs = read(pc) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        return 0;
    }

    private int ZPX() {
        addrAbs = (read(pc) + x) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        return 0;
    }

    private int ZPY() {
        addrAbs = (read(pc) + y) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        return 0;
    }

    private int REL() {
        addrRel = read(pc);
        pc = (pc + 1) & 0xFFFF;
        if ((addrRel & 0x80) != 0) {
            addrRel |= 0xFF00;
        }
        return 0;
    }

    private int ABS() {
        int lo = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int hi = read(pc);
        pc = (pc + 1) & 0xFFFF;
        addrAbs = ((hi << 8) | lo) & 0xFFFF;
        return 0;
    }

    private int ABX() {
        int lo = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int hi = read(pc);
        pc = (pc + 1) & 0xFFFF;
        addrAbs = (((hi << 8) | lo) + x) & 0xFFFF;
        return (addrAbs & 0xFF00) != (hi << 8) ? 1 : 0;
    }

    private int ABY() {
        int lo = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int hi = read(pc);
        pc = (pc + 1) & 0xFFFF;
        addrAbs = (((hi << 8) | lo) + y) & 0xFFFF;
        return (addrAbs & 0xFF00) != (hi << 8) ? 1 : 0;
    }

    private int IND() {
        int ptrLo = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int ptrHi = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int ptr = ((ptrHi << 8) | ptrLo) & 0xFFFF;

        if (ptrLo == 0x00FF) {
            addrAbs = ((read(ptr & 0xFF00) << 8) | read(ptr)) & 0xFFFF;
        } else {
            addrAbs = ((read(ptr + 1) << 8) | read(ptr)) & 0xFFFF;
        }
        return 0;
    }

    private int IZX() {
        int t = read(pc);
        pc = (pc + 1) & 0xFFFF;

        int lo = read((t + x) & 0xFF);
        int hi = read((t + x + 1) & 0xFF);
        addrAbs = ((hi << 8) | lo) & 0xFFFF;
        return 0;
    }

    private int IZY() {
        int t = read(pc);
        pc = (pc + 1) & 0xFFFF;

        int lo = read(t & 0xFF);
        int hi = read((t + 1) & 0xFF);
        addrAbs = (((hi << 8) | lo) + y) & 0xFFFF;
        return (addrAbs & 0xFF00) != (hi << 8) ? 1 : 0;
    }

    private int fetch() {
        Instruction instruction = lookup[opcode];
        if (!instruction.implied) {
            fetched = read(addrAbs);
        }
        return fetched & 0xFF;
    }

    // Instruction implementations --------------------------------------------

    private int ADC() {
        fetch();
        temp = a + fetched + getFlag(FLAG_C);
        setFlag(FLAG_C, temp > 0xFF);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_V, (~((a ^ fetched) & 0xFF) & ((a ^ temp) & 0xFF) & 0x80) != 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        a = temp & 0xFF;
        return 1;
    }

    private int SBC() {
        fetch();
        int value = fetched ^ 0xFF;
        temp = a + value + getFlag(FLAG_C);
        setFlag(FLAG_C, (temp & 0xFF00) != 0);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_V, ((temp ^ a) & (temp ^ value) & 0x80) != 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        a = temp & 0xFF;
        return 1;
    }

    private int AND() {
        fetch();
        a = (a & fetched) & 0xFF;
        setFlag(FLAG_Z, a == 0);
        setFlag(FLAG_N, (a & 0x80) != 0);
        return 1;
    }

    private int ASL() {
        fetch();
        temp = (fetched << 1) & 0x1FE;
        setFlag(FLAG_C, (temp & 0x100) != 0);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        if (lookup[opcode].implied) {
            a = temp & 0xFF;
        } else {
            write(addrAbs, temp);
        }
        return 0;
    }

    private int BCC() {
        if (getFlag(FLAG_C) == 0) {
            cycles++;
            addrAbs = (pc + addrRel) & 0xFFFF;
            if ((addrAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
            pc = addrAbs;
        }
        return 0;
    }

    private int BCS() {
        if (getFlag(FLAG_C) == 1) {
            cycles++;
            addrAbs = (pc + addrRel) & 0xFFFF;
            if ((addrAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
            pc = addrAbs;
        }
        return 0;
    }

    private int BEQ() {
        if (getFlag(FLAG_Z) == 1) {
            cycles++;
            addrAbs = (pc + addrRel) & 0xFFFF;
            if ((addrAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
            pc = addrAbs;
        }
        return 0;
    }

    private int BIT() {
        fetch();
        temp = a & fetched;
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (fetched & 0x80) != 0);
        setFlag(FLAG_V, (fetched & (1 << 6)) != 0);
        return 0;
    }

    private int BMI() {
        if (getFlag(FLAG_N) == 1) {
            cycles++;
            addrAbs = (pc + addrRel) & 0xFFFF;
            if ((addrAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
            pc = addrAbs;
        }
        return 0;
    }

    private int BNE() {
        if (getFlag(FLAG_Z) == 0) {
            cycles++;
            addrAbs = (pc + addrRel) & 0xFFFF;
            if ((addrAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
            pc = addrAbs;
        }
        return 0;
    }

    private int BPL() {
        if (getFlag(FLAG_N) == 0) {
            cycles++;
            addrAbs = (pc + addrRel) & 0xFFFF;
            if ((addrAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
            pc = addrAbs;
        }
        return 0;
    }

    private int BRK() {
        pc = (pc + 1) & 0xFFFF;
        setFlag(FLAG_I, true);
        write(0x0100 + stkp, (pc >> 8) & 0xFF);
        stkp = (stkp - 1) & 0xFF;
        write(0x0100 + stkp, pc & 0xFF);
        stkp = (stkp - 1) & 0xFF;

        setFlag(FLAG_B, true);
        write(0x0100 + stkp, status);
        stkp = (stkp - 1) & 0xFF;
        setFlag(FLAG_B, false);

        int lo = read(0xFFFE);
        int hi = read(0xFFFF);
        pc = ((hi << 8) | lo) & 0xFFFF;
        return 0;
    }

    private int BVC() {
        if (getFlag(FLAG_V) == 0) {
            cycles++;
            addrAbs = (pc + addrRel) & 0xFFFF;
            if ((addrAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
            pc = addrAbs;
        }
        return 0;
    }

    private int BVS() {
        if (getFlag(FLAG_V) == 1) {
            cycles++;
            addrAbs = (pc + addrRel) & 0xFFFF;
            if ((addrAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
            pc = addrAbs;
        }
        return 0;
    }

    private int CLC() {
        setFlag(FLAG_C, false);
        return 0;
    }

    private int CLD() {
        setFlag(FLAG_D, false);
        return 0;
    }

    private int CLI() {
        setFlag(FLAG_I, false);
        return 0;
    }

    private int CLV() {
        setFlag(FLAG_V, false);
        return 0;
    }

    private int CMP() {
        fetch();
        temp = (a - fetched) & 0x1FF;
        setFlag(FLAG_C, a >= fetched);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        return 1;
    }

    private int CPX() {
        fetch();
        temp = (x - fetched) & 0x1FF;
        setFlag(FLAG_C, x >= fetched);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        return 0;
    }

    private int CPY() {
        fetch();
        temp = (y - fetched) & 0x1FF;
        setFlag(FLAG_C, y >= fetched);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        return 0;
    }

    private int DEC() {
        fetch();
        temp = (fetched - 1) & 0xFF;
        write(addrAbs, temp);
        setFlag(FLAG_Z, temp == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        return 0;
    }

    private int DEX() {
        x = (x - 1) & 0xFF;
        setFlag(FLAG_Z, x == 0);
        setFlag(FLAG_N, (x & 0x80) != 0);
        return 0;
    }

    private int DEY() {
        y = (y - 1) & 0xFF;
        setFlag(FLAG_Z, y == 0);
        setFlag(FLAG_N, (y & 0x80) != 0);
        return 0;
    }

    private int EOR() {
        fetch();
        a = (a ^ fetched) & 0xFF;
        setFlag(FLAG_Z, a == 0);
        setFlag(FLAG_N, (a & 0x80) != 0);
        return 1;
    }

    private int INC() {
        fetch();
        temp = (fetched + 1) & 0xFF;
        write(addrAbs, temp);
        setFlag(FLAG_Z, temp == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        return 0;
    }

    private int INX() {
        x = (x + 1) & 0xFF;
        setFlag(FLAG_Z, x == 0);
        setFlag(FLAG_N, (x & 0x80) != 0);
        return 0;
    }

    private int INY() {
        y = (y + 1) & 0xFF;
        setFlag(FLAG_Z, y == 0);
        setFlag(FLAG_N, (y & 0x80) != 0);
        return 0;
    }

    private int JMP() {
        pc = addrAbs;
        return 0;
    }

    private int JSR() {
        pc = (pc - 1) & 0xFFFF;
        write(0x0100 + stkp, (pc >> 8) & 0xFF);
        stkp = (stkp - 1) & 0xFF;
        write(0x0100 + stkp, pc & 0xFF);
        stkp = (stkp - 1) & 0xFF;
        pc = addrAbs;
        return 0;
    }

    private int LDA() {
        fetch();
        a = fetched & 0xFF;
        setFlag(FLAG_Z, a == 0);
        setFlag(FLAG_N, (a & 0x80) != 0);
        return 1;
    }

    private int LDX() {
        fetch();
        x = fetched & 0xFF;
        setFlag(FLAG_Z, x == 0);
        setFlag(FLAG_N, (x & 0x80) != 0);
        return 1;
    }

    private int LDY() {
        fetch();
        y = fetched & 0xFF;
        setFlag(FLAG_Z, y == 0);
        setFlag(FLAG_N, (y & 0x80) != 0);
        return 1;
    }

    private int LSR() {
        fetch();
        setFlag(FLAG_C, (fetched & 0x01) != 0);
        temp = (fetched >> 1) & 0xFF;
        setFlag(FLAG_Z, temp == 0);
        setFlag(FLAG_N, false);
        if (lookup[opcode].implied) {
            a = temp;
        } else {
            write(addrAbs, temp);
        }
        return 0;
    }

    private int NOP() {
        switch (opcode & 0xFF) {
            case 0x1C:
            case 0x3C:
            case 0x5C:
            case 0x7C:
            case 0xDC:
            case 0xFC:
                return 1;
            default:
                return 0;
        }
    }

    private int ORA() {
        fetch();
        a = (a | fetched) & 0xFF;
        setFlag(FLAG_Z, a == 0);
        setFlag(FLAG_N, (a & 0x80) != 0);
        return 1;
    }

    private int PHA() {
        write(0x0100 + stkp, a);
        stkp = (stkp - 1) & 0xFF;
        return 0;
    }

    private int PHP() {
        write(0x0100 + stkp, status | FLAG_B | FLAG_U);
        setFlag(FLAG_B, false);
        setFlag(FLAG_U, false);
        stkp = (stkp - 1) & 0xFF;
        return 0;
    }

    private int PLA() {
        stkp = (stkp + 1) & 0xFF;
        a = read(0x0100 + stkp);
        setFlag(FLAG_Z, a == 0);
        setFlag(FLAG_N, (a & 0x80) != 0);
        return 0;
    }

    private int PLP() {
        stkp = (stkp + 1) & 0xFF;
        status = read(0x0100 + stkp);
        setFlag(FLAG_U, true);
        return 0;
    }

    private int ROL() {
        fetch();
        temp = ((fetched << 1) | getFlag(FLAG_C)) & 0x1FF;
        setFlag(FLAG_C, (temp & 0x100) != 0);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        if (lookup[opcode].implied) {
            a = temp & 0xFF;
        } else {
            write(addrAbs, temp);
        }
        return 0;
    }

    private int ROR() {
        fetch();
        temp = ((getFlag(FLAG_C) << 7) | (fetched >> 1)) & 0xFF;
        setFlag(FLAG_C, (fetched & 0x01) != 0);
        setFlag(FLAG_Z, temp == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        if (lookup[opcode].implied) {
            a = temp;
        } else {
            write(addrAbs, temp);
        }
        return 0;
    }

    private int RTI() {
        stkp = (stkp + 1) & 0xFF;
        status = read(0x0100 + stkp);
        status &= ~FLAG_B;
        status |= FLAG_U;

        stkp = (stkp + 1) & 0xFF;
        pc = read(0x0100 + stkp);
        stkp = (stkp + 1) & 0xFF;
        pc |= (read(0x0100 + stkp) << 8);
        pc &= 0xFFFF;
        return 0;
    }

    private int RTS() {
        stkp = (stkp + 1) & 0xFF;
        pc = read(0x0100 + stkp);
        stkp = (stkp + 1) & 0xFF;
        pc |= (read(0x0100 + stkp) << 8);
        pc = (pc + 1) & 0xFFFF;
        return 0;
    }

    private int SEC() {
        setFlag(FLAG_C, true);
        return 0;
    }

    private int SED() {
        setFlag(FLAG_D, true);
        return 0;
    }

    private int SEI() {
        setFlag(FLAG_I, true);
        return 0;
    }

    private int STA() {
        write(addrAbs, a);
        return 0;
    }

    private int STX() {
        write(addrAbs, x);
        return 0;
    }

    private int STY() {
        write(addrAbs, y);
        return 0;
    }

    private int TAX() {
        x = a & 0xFF;
        setFlag(FLAG_Z, x == 0);
        setFlag(FLAG_N, (x & 0x80) != 0);
        return 0;
    }

    private int TAY() {
        y = a & 0xFF;
        setFlag(FLAG_Z, y == 0);
        setFlag(FLAG_N, (y & 0x80) != 0);
        return 0;
    }

    private int TSX() {
        x = stkp & 0xFF;
        setFlag(FLAG_Z, x == 0);
        setFlag(FLAG_N, (x & 0x80) != 0);
        return 0;
    }

    private int TXA() {
        a = x & 0xFF;
        setFlag(FLAG_Z, a == 0);
        setFlag(FLAG_N, (a & 0x80) != 0);
        return 0;
    }

    private int TXS() {
        stkp = x & 0xFF;
        return 0;
    }

    private int TYA() {
        a = y & 0xFF;
        setFlag(FLAG_Z, a == 0);
        setFlag(FLAG_N, (a & 0x80) != 0);
        return 0;
    }

    private int XXX() {
        return 0;
    }

    private enum AddressingModeType {
        IMP,
        IMM,
        ZP0,
        ZPX,
        ZPY,
        REL,
        ABS,
        ABX,
        ABY,
        IND,
        IZX,
        IZY
    }

    private interface Operation {
        int apply();
    }

    private interface AddressingMode {
        int apply();
    }

    private final class Instruction {
        final String name;
        final Operation operate;
        final AddressingMode addrmode;
        final AddressingModeType addrmodeType;
        final boolean implied;
        final int cycles;

        Instruction(String name, Operation operate, AddressingMode addrmode,
                    AddressingModeType type, boolean implied, int cycles) {
            this.name = name;
            this.operate = operate;
            this.addrmode = addrmode;
            this.addrmodeType = type;
            this.implied = implied;
            this.cycles = cycles;
        }
    }
}
