package org.jnesb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;

import org.jnesb.bus.NesBus;
import org.jnesb.cartridge.Cartridge;
import org.jnesb.ppu.Ppu2C02;
import org.jnesb.ui.JavaFxNesEmulator;

public final class App {

    private static final int DEFAULT_FRAME_COUNT = 1;

    private App() {
        // Utility class
    }

    public static void main(String[] args) {
        CliOptions options = CliOptions.parse(args);
        if (options == null) {
            printUsage();
            return;
        }

        Path romPath = options.romPath;
        boolean hasRom = romPath != null;

        if (options.headless && !hasRom) {
            System.err.println("Headless mode requires a ROM path.");
            return;
        }

        NesBus bus = new NesBus();
        Cartridge cartridge = null;

        if (hasRom) {
            if (!Files.exists(romPath)) {
                System.err.printf(Locale.ROOT, "ROM not found: %s%n", romPath);
                return;
            }
            try {
                cartridge = Cartridge.load(romPath);
                if (!cartridge.isImageValid()) {
                    System.err.printf(Locale.ROOT, "ROM image is invalid: %s%n", romPath);
                    return;
                }
                bus.insertCartridge(cartridge);
            } catch (IOException ex) {
                System.err.printf(Locale.ROOT, "Failed to load ROM %s: %s%n", romPath, ex.getMessage());
                return;
            }
        }

        if (options.headless) {
            bus.reset();
            long ticks = runForFrames(bus, options.framesToRun);
            System.out.printf(Locale.ROOT,
                    "Completed %d frame(s) for %s in %,d PPU cycles.%n",
                    options.framesToRun, Objects.requireNonNull(romPath).getFileName(), ticks);
            return;
        }

        bus.reset();
        if (options.framesToRun != DEFAULT_FRAME_COUNT) {
            System.out.println("--frames option is ignored in graphical mode.");
        }
        if (!hasRom) {
            System.out.println("No ROM provided. Use Game > Load Game to open one.");
        }
        launchJavaFx(bus, romPath);
    }

    private static long runForFrames(NesBus bus, int frames) {
        Ppu2C02 ppu = bus.ppu();
        ppu.clearFrameFlag();

        long ticks = 0;
        long maxTicks = Math.max(frames, 1) * 341L * 262L * 4L;
        int completedFrames = 0;

        while (completedFrames < frames && ticks < maxTicks) {
            bus.clock();
            ticks++;
            if (ppu.isFrameComplete()) {
                completedFrames++;
                ppu.clearFrameFlag();
            }
        }

        if (completedFrames < frames) {
            System.err.printf(Locale.ROOT,
                    "Stopped after %,d PPU cycles without completing %d frame(s).%n",
                    ticks, frames);
        }

        return ticks;
    }

    private static void launchJavaFx(NesBus bus, Path romPath) {
        try {
            JavaFxNesEmulator.launchWith(bus, romPath);
            String description = romPath != null ? romPath.getFileName().toString() : "no ROM loaded";
            System.out.printf(Locale.ROOT,
                    "Emulation session ended (%s).%n", description);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            System.err.println("Emulation interrupted; shutting down.");
        }
    }

    private static void printUsage() {
        System.out.println("jNESb - NES emulator");
        System.out.println("Usage: java -jar jNESb.jar [path-to-rom] [--headless] [--frames=N]");
        System.out.println("Headless mode requires a ROM path.");
    }

    private record CliOptions(Path romPath, int framesToRun, boolean headless) {

        static CliOptions parse(String[] args) {
            Path rom = null;
            int frames = DEFAULT_FRAME_COUNT;
            boolean headless = false;

            for (String arg : args) {
                if (arg.startsWith("--frames=")) {
                    String value = arg.substring("--frames=".length());
                    frames = parseFrameCount(value);
                    if (frames <= 0) {
                        System.err.printf(Locale.ROOT,
                                "Frame count must be positive (got %s).%n", value);
                        return null;
                    }
                } else if ("--headless".equals(arg)) {
                    headless = true;
                } else if (arg.startsWith("--")) {
                    System.err.printf(Locale.ROOT, "Unknown option: %s%n", arg);
                    return null;
                } else if (rom == null) {
                    rom = Paths.get(arg).toAbsolutePath().normalize();
                } else {
                    System.err.printf(Locale.ROOT,
                            "Unexpected argument after ROM path: %s%n", arg);
                    return null;
                }
            }

            return new CliOptions(rom, frames, headless);
        }

        private static int parseFrameCount(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                System.err.printf(Locale.ROOT,
                        "Invalid frame count \"%s\": %s%n", value, ex.getMessage());
                return -1;
            }
        }
    }
}
