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
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
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
    private double filteredLeftSample;
    private double filteredRightSample;
    private boolean filterLeftInitialized;
    private boolean filterRightInitialized;
    private double hp90PrevInputLeft;
    private double hp90PrevOutputLeft;
    private double hp440PrevInputLeft;
    private double hp440PrevOutputLeft;
    private double hp90PrevInputRight;
    private double hp90PrevOutputRight;
    private double hp440PrevInputRight;
    private double hp440PrevOutputRight;

    public void start() {
        if (started.get()) {
            return;
        }
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, buffer.length * 4);
            line.start();
            started.set(true);
            filteredLeftSample = 0.0;
            filteredRightSample = 0.0;
            filterLeftInitialized = false;
            filterRightInitialized = false;
            hp90PrevInputLeft = hp90PrevOutputLeft = 0.0;
            hp440PrevInputLeft = hp440PrevOutputLeft = 0.0;
            hp90PrevInputRight = hp90PrevOutputRight = 0.0;
            hp440PrevInputRight = hp440PrevOutputRight = 0.0;
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

    public void submitSample(double left, double right) {
        if (!started.get()) {
            return;
        }
        double filteredLeft = applyLowPass(applyHighPassChain(left, true), true);
        double filteredRight = applyLowPass(applyHighPassChain(right, false), false);
        short leftValue = toPcm(filteredLeft);
        short rightValue = toPcm(filteredRight);
        buffer[bufferIndex++] = (byte) (leftValue & 0xFF);
        buffer[bufferIndex++] = (byte) ((leftValue >> 8) & 0xFF);
        buffer[bufferIndex++] = (byte) (rightValue & 0xFF);
        buffer[bufferIndex++] = (byte) ((rightValue >> 8) & 0xFF);
        if (bufferIndex >= buffer.length) {
            flush();
        }
    }
    private double applyLowPass(double input, boolean leftChannel) {
        if (leftChannel) {
            if (!filterLeftInitialized) {
                filteredLeftSample = input;
                filterLeftInitialized = true;
                return input;
            }
            filteredLeftSample += LOW_PASS_ALPHA * (input - filteredLeftSample);
            return filteredLeftSample;
        }
        if (!filterRightInitialized) {
            filteredRightSample = input;
            filterRightInitialized = true;
            return input;
        }
        filteredRightSample += LOW_PASS_ALPHA * (input - filteredRightSample);
        return filteredRightSample;
    }

    private double applyHighPassChain(double input, boolean leftChannel) {
        if (leftChannel) {
            hp90PrevOutputLeft = input - hp90PrevInputLeft + HIGH_PASS_ALPHA_90 * hp90PrevOutputLeft;
            hp90PrevInputLeft = input;
            hp440PrevOutputLeft = hp90PrevOutputLeft - hp440PrevInputLeft + HIGH_PASS_ALPHA_440 * hp440PrevOutputLeft;
            hp440PrevInputLeft = hp90PrevOutputLeft;
            return hp440PrevOutputLeft;
        }
        hp90PrevOutputRight = input - hp90PrevInputRight + HIGH_PASS_ALPHA_90 * hp90PrevOutputRight;
        hp90PrevInputRight = input;
        hp440PrevOutputRight = hp90PrevOutputRight - hp440PrevInputRight + HIGH_PASS_ALPHA_440 * hp440PrevOutputRight;
        hp440PrevInputRight = hp90PrevOutputRight;
        return hp440PrevOutputRight;
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
