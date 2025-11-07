package org.jnesb;

/**
 * Shared audio configuration constants derived from the NTSC NES hardware.
 */
public final class AudioConfig {

    public static final float SAMPLE_RATE = 44_100.0f;
    public static final double CPU_FREQUENCY_NTSC = 1_789_773.0;
    public static final double CPU_CYCLES_PER_SAMPLE = CPU_FREQUENCY_NTSC / SAMPLE_RATE;

    private AudioConfig() {
    }
}
