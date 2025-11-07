package org.jnesb.cartridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

final class CartridgeRamTest {

    @Test
    void cpuReadsAndWritesPrgRam() throws IOException {
        byte[] romImage = buildRomImage();
        Cartridge cartridge = Cartridge.load(new ByteArrayInputStream(romImage));

        int[] data = new int[1];
        cartridge.cpuWrite(0x6000, 0xAB);
        cartridge.cpuRead(0x6000, data);
        assertEquals(0xAB, data[0]);
    }

    private static byte[] buildRomImage() {
        byte[] header = new byte[16];
        header[0] = 'N';
        header[1] = 'E';
        header[2] = 'S';
        header[3] = 0x1A;
        header[4] = 0x01; // PRG
        header[5] = 0x00; // CHR

        byte[] prg = new byte[16 * 1024];
        byte[] image = new byte[header.length + prg.length];
        System.arraycopy(header, 0, image, 0, header.length);
        System.arraycopy(prg, 0, image, header.length, prg.length);
        return image;
    }
}
