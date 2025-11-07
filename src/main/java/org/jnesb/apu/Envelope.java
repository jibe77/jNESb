package org.jnesb.apu;

final class Envelope {
    private boolean loop;
    private boolean constantVolume;
    private int volume;
    private boolean start;
    private int divider;
    private int decayLevel = 15;
    
    void write(int data) {
        loop = (data & 0x20) != 0;
        constantVolume = (data & 0x10) != 0;
        volume = data & 0x0F;
    }
    
    void start() {
        start = true;
    }
    
    void clock() {
        if (start) {
            start = false;
            decayLevel = 15;
            divider = volume;
            return;
        }
        
        if (divider == 0) {
            divider = volume;
            if (decayLevel == 0) {
                if (loop) {
                    decayLevel = 15;
                }
            } else {
                decayLevel--;
            }
        } else {
            divider--;
        }
    }
    
    int output() {
        return constantVolume ? volume : decayLevel;
    }
    
    boolean isLoopEnabled() {
        return loop;
    }
    
    /**
     * Reset l'enveloppe à son état initial
     */
    void reset() {
        loop = false;
        constantVolume = false;
        volume = 0;
        start = false;
        divider = 0;
        decayLevel = 15;
    }
}