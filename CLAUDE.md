# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

jNESb is a Java-based NES (Nintendo Entertainment System) emulator that re-creates the 6502 CPU, PPU (Picture Processing Unit), APU (Audio Processing Unit), and I/O bus to execute commercial NES ROMs.

## Build and Run Commands

### Build
```bash
mvn clean package
```

### Run Tests
```bash
mvn test
```

### Run Single Test
```bash
mvn test -Dtest=ClassName#methodName
# Example: mvn test -Dtest=Cpu6502Test#testLDA
```

### Run Emulator (GUI)
```bash
mvn -q exec:java -Dexec.mainClass=org.jnesb.App -Dexec.args="<path-to-rom>"
```

### Run Headless (for testing/benchmarks)
```bash
java -cp target/jNESb-0.1.0-SNAPSHOT.jar org.jnesb.App <path-to-rom> --headless [--frames=N]
```

## Architecture

The emulator uses a modular, component-based architecture coordinated through the system bus:

```
JavaFxNesEmulator (UI) → NesBus (System Bus) → CPU 6502 / PPU 2C02 / APU
                                            → Cartridge & Mappers
                                            → Input Controllers
```

### Core Packages

- **org.jnesb.cpu** - 6502 processor with all addressing modes and 48 instruction opcodes
- **org.jnesb.ppu** - PPU pipeline: background/sprite rendering, OAM DMA, palette management
- **org.jnesb.apu** - Audio: 2 pulse channels, triangle, noise, DMC; outputs 44.1 kHz PCM
- **org.jnesb.cartridge** - iNES format loader with mappers (0, 1, 2, 3, 4, 66)
- **org.jnesb.bus** - Central coordinator (NesBus) routing CPU/PPU memory access
- **org.jnesb.input** - NesController (8-button) and NesZapper (light gun)
- **org.jnesb.ui** - JavaFX application with 3x scaled rendering

### Key Patterns

**Cycle-Accurate Emulation**: All components clock synchronously via `bus.clock()`. PPU cycles drive frame completion. Timing precision is critical.

**Stateful Interface**: Core components implement `Stateful` for save/load state:
```java
public interface Stateful {
    byte[] saveState();
    void loadState(byte[] data);
    int stateSize();
}
```

**Memory Mapping**: NesBus implements CpuBus interface with:
- 2KB CPU RAM (mirrored to 8KB)
- PPU registers ($2000-$2007)
- APU & input registers ($4000-$4017)
- Cartridge PRG ROM/RAM

**Mapper Plugin System**: Abstract `Mapper` class handles PRG/CHR banking, mirroring, and IRQ generation.

## Testing

Tests use:
- `RamBus` mock for isolated CPU testing
- Test ROMs in `src/test/resources/roms/` including:
  - `nestest.nes` for CPU instruction validation
  - Blargg APU tests for audio precision
  - Real game samples for mapper verification

## Requirements

- JDK 21+ (targets release level 25)
- Maven 3.9.11+
- JavaFX (platform-specific: mac/linux/windows)

## Input Controls

- **Z** = B, **X** = A
- **A** = Select, **S** = Start
- **Arrow keys** = D-Pad

## Attribution

Java port of OneLoneCoder/javidx9's olcNES (OLC-3 license).
