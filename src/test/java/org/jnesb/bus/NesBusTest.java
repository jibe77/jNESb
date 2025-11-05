package org.jnesb.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.jnesb.cartridge.Cartridge;
import org.junit.jupiter.api.Test;

final class NesBusTest {

    @Test
    void cartridgeLoadAndBusAccessWorkForMapper0() throws IOException {
        byte[] romImage = buildTestRom();
        Cartridge cartridge = Cartridge.load(new ByteArrayInputStream(romImage));
        assertTrue(cartridge.isImageValid());
        assertEquals(0, cartridge.mapperId());
        assertEquals(1, cartridge.prgBankCount());
        assertEquals(1, cartridge.chrBankCount());

        NesBus bus = new NesBus();
        bus.insertCartridge(cartridge);
        bus.reset();

        // CPU RAM mirrors every 2KB
        bus.write(0x0000, 0xAA);
        assertEquals(0xAA, bus.read(0x0000, false));
        assertEquals(0xAA, bus.read(0x0800, false));

        // Cartridge PRG mapping
        int value = bus.read(0x8004, false);
        assertEquals(0x04, value);

        // Cartridge PPU mapping
        int chrValue = bus.ppu().ppuRead(0x0010, false);
        assertEquals(0x10, chrValue);
    }

    private static byte[] buildTestRom() {
        byte[] header = new byte[16];
        header[0] = 'N';
        header[1] = 'E';
        header[2] = 'S';
        header[3] = 0x1A;
        header[4] = 0x01; // 16KB PRG
        header[5] = 0x01; // 8KB CHR
        header[6] = 0x00;
        header[7] = 0x00;

        byte[] prg = new byte[16 * 1024];
        for (int i = 0; i < prg.length; i++) {
            prg[i] = (byte) (i & 0xFF);
        }

        byte[] chr = new byte[8 * 1024];
        for (int i = 0; i < chr.length; i++) {
            chr[i] = (byte) (i & 0xFF);
        }

        byte[] image = new byte[header.length + prg.length + chr.length];
        int offset = 0;
        System.arraycopy(header, 0, image, offset, header.length);
        offset += header.length;
        System.arraycopy(prg, 0, image, offset, prg.length);
        offset += prg.length;
        System.arraycopy(chr, 0, image, offset, chr.length);
        return image;
    }
}
