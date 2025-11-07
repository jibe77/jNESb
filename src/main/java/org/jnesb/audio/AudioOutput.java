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

    private final byte[] buffer = new byte[4096];
    private int bufferIndex = 0;

    private SourceDataLine line;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public void start() {
        if (started.get()) {
            return;
        }
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, buffer.length * 4);
            line.start();
            started.set(true);
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
        double clamped = Math.max(-1.0, Math.min(1.0, sample));
        short value = (short) Math.round(clamped * 32767);
        buffer[bufferIndex++] = (byte) (value & 0xFF);
        buffer[bufferIndex++] = (byte) ((value >> 8) & 0xFF);
        if (bufferIndex >= buffer.length) {
            flush();
        }
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
