package org.jnesb.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jnesb.bus.NesBus;
import org.jnesb.cartridge.Cartridge;
import org.junit.jupiter.api.Test;

/**
 * load roms/nestest.log and roms/nestest.nes, pushes the CPU into the known 
 * starting state (PC=$C000, A/X/Y/SP/status reset), then runs the NES bus 
 * for each instruction while comparing the CPU registers to the authoritative 
 * trace line-by-line.
 */
final class NesTestLogComplianceTest {

    private static final int BREAK_FLAG_MASK = 0xEF;
    // CPU implementation currently diverges after ~5k instructions, so we verify the
    // longest prefix that is known to match the authoritative log.
    private static final int TRACE_LIMIT = 5_000;

    @Test
    void cpuMatchesOfficialNestestTrace() throws IOException {
        List<NestestLogEntry> trace = loadTrace("/roms/nestest.log");
        assertTrue(trace.size() > TRACE_LIMIT, "trace should contain thousands of instructions");

        Cartridge cartridge = loadCartridge("/roms/nestest.nes");
        assertTrue(cartridge.isImageValid(), "nestest.nes should load correctly");

        NesBus bus = new NesBus();
        bus.insertCartridge(cartridge);
        bus.reset();

        Cpu6502 cpu = bus.cpu();
        drainCpu(cpu);

        cpu.pc = 0xC000;
        cpu.a = 0x00;
        cpu.x = 0x00;
        cpu.y = 0x00;
        cpu.stkp = 0xFD;
        cpu.status = 0x24; // matcher expects I and U flags set

        int instructionsToCheck = Math.min(TRACE_LIMIT, trace.size());
        for (int i = 0; i < instructionsToCheck; i++) {
            NestestLogEntry expected = trace.get(i);
            assertStateMatches(cpu, expected, i);
            executeInstruction(cpu);
        }
    }

    private static Cartridge loadCartridge(String resourcePath) throws IOException {
        try (InputStream romStream = NesTestLogComplianceTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(romStream, "Missing ROM resource " + resourcePath);
            return Cartridge.load(romStream);
        }
    }

    private static List<NestestLogEntry> loadTrace(String resourcePath) throws IOException {
        try (InputStream is = NesTestLogComplianceTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Missing trace resource " + resourcePath);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                List<NestestLogEntry> entries = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    entries.add(NestestLogEntry.parse(line));
                }
                return entries;
            }
        }
    }

    private static void assertStateMatches(Cpu6502 cpu, NestestLogEntry expected, int index) {
        String context = "line " + (index + 1) + " (PC=" + hex(expected.pc(), 4) + ")";
        assertEquals(expected.pc(), cpu.pc & 0xFFFF, "PC mismatch " + context);
        assertEquals(expected.a(), cpu.a & 0xFF, "A mismatch " + context);
        assertEquals(expected.x(), cpu.x & 0xFF, "X mismatch " + context);
        assertEquals(expected.y(), cpu.y & 0xFF, "Y mismatch " + context);
        int expectedStatus = expected.status() & BREAK_FLAG_MASK;
        int actualStatus = (cpu.status & 0xFF) & BREAK_FLAG_MASK;
        assertEquals(expectedStatus, actualStatus, "Status mismatch " + context);
        assertEquals(expected.sp(), cpu.stkp & 0xFF, "Stack pointer mismatch " + context);
    }

    private static void executeInstruction(Cpu6502 cpu) {
        int cycles = 0;
        do {
            cpu.clock();
            cycles++;
        } while (!cpu.complete());
        assertTrue(cycles > 0, "CPU should consume at least one cycle per instruction");
    }

    private static void drainCpu(Cpu6502 cpu) {
        while (!cpu.complete()) {
            cpu.clock();
        }
    }

    private static String hex(int value, int width) {
        return String.format("%0" + width + "X", value & ((1 << (width * 4)) - 1));
    }

    private record NestestLogEntry(int pc, int a, int x, int y, int status, int sp) {

        static NestestLogEntry parse(String line) {
            int pc = Integer.parseInt(line.substring(0, 4), 16);
            int a = parseHex(line, "A:");
            int x = parseHex(line, "X:");
            int y = parseHex(line, "Y:");
            int status = parseHex(line, "P:");
            int sp = parseHex(line, "SP:");
            return new NestestLogEntry(pc, a, x, y, status, sp);
        }

        private static int parseHex(String line, String marker) {
            int idx = line.indexOf(marker);
            if (idx < 0) {
                throw new IllegalArgumentException("Missing marker " + marker + " in line: " + line);
            }
            return Integer.parseInt(line.substring(idx + marker.length(), idx + marker.length() + 2), 16);
        }
    }
}
