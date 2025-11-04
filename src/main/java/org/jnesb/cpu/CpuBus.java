package org.jnesb.cpu;

/**
 * Communication interface between the CPU core and the system bus.
 * Implementations must provide read/write access to memory mapped devices.
 */
public interface CpuBus {

    /**
     * Reads a byte from the bus at the specified address.
     *
     * @param address 16-bit address
     * @param readOnly when true, read must not mutate bus state
     * @return value in the range 0x00-0xFF
     */
    int read(int address, boolean readOnly);

    /**
     * Convenience overload that performs a normal (mutable) read.
     *
     * @param address 16-bit address
     * @return value in the range 0x00-0xFF
     */
    default int read(int address) {
        return read(address, false);
    }

    /**
     * Writes a byte to the bus.
     *
     * @param address 16-bit address
     * @param data value in the range 0x00-0xFF
     */
    void write(int address, int data);
}
