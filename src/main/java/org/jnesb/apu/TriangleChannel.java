package org.jnesb.apu;

/**
 * Canal Triangle NES corrigé pour les sons graves
 */
final class TriangleChannel {
    
    // Séquence triangulaire 32-step
    private static final int[] SEQUENCE = {
        15, 14, 13, 12, 11, 10, 9, 8,
        7, 6, 5, 4, 3, 2, 1, 0,
        0, 1, 2, 3, 4, 5, 6, 7,
        8, 9, 10, 11, 12, 13, 14, 15
    };
    
 final LengthCounter lengthCounter = new LengthCounter();
    
    private boolean controlFlag;
    private int linearReloadValue;
     int linearCounter;
    private boolean linearReload;
    private boolean enabled;
    private int timer;
     int timerReload;
    private int sequenceIndex;
    
    void writeControl(int data) {
        controlFlag = (data & 0x80) != 0;
        linearReloadValue = data & 0x7F;
        lengthCounter.setHalt(controlFlag);
    }
    
    void writeTimerLow(int data) {
        timerReload = (timerReload & 0xFF00) | (data & 0xFF);
    }
    
    void writeTimerHigh(int data) {
        timerReload = (timerReload & 0x00FF) | ((data & 0x07) << 8);
        lengthCounter.load((data >> 3) & 0x1F);
        timer = timerReload;
        linearReload = true;
    }
    
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            lengthCounter.clear();
            linearCounter = 0;
        }
    }
    
    /**
     * BUG CORRIGÉ: Le timer décrémente toujours, mais le séquenceur
     * n'avance que si les conditions sont remplies
     */
    void clockTimer() {
        // Le timer décrémente toujours
        if (timer == 0) {
            timer = timerReload;
            
            // Le séquenceur n'avance que si:
            // - Le canal est activé
            // - Le length counter n'est pas à 0
            // - Le linear counter n'est pas à 0
            // - Le timer n'est pas trop bas (ultrason silencing)
            if (enabled && lengthCounter.value() > 0 && linearCounter > 0 && timerReload >= 2) {
                sequenceIndex = (sequenceIndex + 1) & 0x1F;
            }
        } else {
            timer--;
        }
    }
    
    void quarterFrame() {
        if (linearReload) {
            linearCounter = linearReloadValue;
        } else if (linearCounter > 0) {
            linearCounter--;
        }
        
        if (!controlFlag) {
            linearReload = false;
        }
    }
    
    void halfFrame() {
        lengthCounter.clock(enabled);
    }
    
    /**
     * Sortie du canal triangle
     */
    int output() {
        if (!enabled || lengthCounter.value() == 0 || linearCounter == 0) {
            return 0;
        }
        
        // Ultrason silencing: fréquences trop hautes sont silencées
        if (timerReload < 2) {
            return 7; // Valeur moyenne pour éviter les pops
        }
        
        return SEQUENCE[sequenceIndex];
    }
    
    boolean isActive() {
        return enabled && lengthCounter.value() > 0;
    }
    
    /**
     * Reset le canal
     */
    void reset() {
        enabled = false;
        controlFlag = false;
        linearReloadValue = 0;
        linearCounter = 0;
        linearReload = false;
        timer = 0;
        timerReload = 0;
        sequenceIndex = 0;
        lengthCounter.clear();
    }
}