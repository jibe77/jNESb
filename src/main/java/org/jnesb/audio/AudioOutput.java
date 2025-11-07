package org.jnesb.audio;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import org.jnesb.AudioConfig;

/**
 * Minimal PCM audio output for the NES APU sample stream.
 */
public final class AudioOutput implements AutoCloseable {

    public static final float SAMPLE_RATE = AudioConfig.SAMPLE_RATE;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    private static final double LOW_PASS_CUTOFF_HZ = 14_000.0;
    private static final double RC = 1.0 / (2.0 * Math.PI * LOW_PASS_CUTOFF_HZ);
    private static final double LOW_PASS_ALPHA = (1.0 / SAMPLE_RATE) / (RC + (1.0 / SAMPLE_RATE));
    private static final double HIGH_PASS_ALPHA_90 = 0.999;
    private static final double HIGH_PASS_ALPHA_440 = 0.996;
    private static final int BUFFER_SIZE_BYTES = 1024;

    private final byte[] buffer = new byte[BUFFER_SIZE_BYTES];
    private int bufferIndex = 0;

    private SourceDataLine line;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private double filteredSample;
    private boolean filterInitialized;
    private double hp90PrevInput;
    private double hp90PrevOutput;
    private double hp440PrevInput;
    private double hp440PrevOutput;

    public void start() {
        if (started.get()) {
            return;
        }
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, buffer.length * 4);
            line.start();
            started.set(true);
            filteredSample = 0.0;
            filterInitialized = false;
            hp90PrevInput = hp90PrevOutput = 0.0;
            hp440PrevInput = hp440PrevOutput = 0.0;
        } catch (LineUnavailableException ex) {
            throw new IllegalStateException("Unable to open audio device", ex);
        }
    }

    public void stop() {
        if (!started.get()) {
            return;
        }
        flush();
        line.stop();
        line.flush();
        started.set(false);
    }

    public void submitSample(double sample) {
        if (!started.get()) {
            return;
        }
        double processed = applyLowPass(applyHighPassChain(sample));
        short value = toPcm(processed);
        buffer[bufferIndex++] = (byte) (value & 0xFF);
        buffer[bufferIndex++] = (byte) ((value >> 8) & 0xFF);
        if (bufferIndex >= buffer.length) {
            flush();
        }
    }

    private double applyLowPass(double input) {
        if (!filterInitialized) {
            filteredSample = input;
            filterInitialized = true;
            return input;
        }
        filteredSample += LOW_PASS_ALPHA * (input - filteredSample);
        return filteredSample;
    }

    private double applyHighPassChain(double input) {
        hp90PrevOutput = input - hp90PrevInput + HIGH_PASS_ALPHA_90 * hp90PrevOutput;
        hp90PrevInput = input;
        hp440PrevOutput = hp90PrevOutput - hp440PrevInput + HIGH_PASS_ALPHA_440 * hp440PrevOutput;
        hp440PrevInput = hp90PrevOutput;
        return hp440PrevOutput;
    }

    private static short toPcm(double sample) {
        double softClipped = sample / (1.0 + Math.abs(sample));
        double clamped = Math.max(-1.0, Math.min(1.0, softClipped));
        return (short) Math.round(clamped * 32767.0);
    }

    private void flush() {
        if (bufferIndex > 0 && line != null) {
            line.write(buffer, 0, bufferIndex);
            bufferIndex = 0;
        }
    }

    @Override
    public void close() {
        stop();
        if (line != null) {
            line.close();
            line = null;
        }
    }
}
