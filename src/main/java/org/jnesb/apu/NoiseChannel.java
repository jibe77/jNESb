package org.jnesb.apu;

/**
 * Canal de bruit NES avec LFSR 15-bit corrigé
 */
final class NoiseChannel {
    
    // Table des périodes NTSC en cycles CPU
    private static final int[] PERIOD_TABLE = {
        4, 8, 16, 32, 64, 96, 128, 160,
        202, 254, 380, 508, 762, 1016, 2034, 4068
    };
    
    private final Envelope envelope = new Envelope();
    private final LengthCounter lengthCounter = new LengthCounter();
    
    private boolean enabled;
    private boolean shortMode; // Mode du feedback (bit 1 vs bit 6)
    private int timerPeriod = PERIOD_TABLE[0];
    private int timerCounter;
    private int shiftRegister = 1; // LFSR 15-bit, initialisé à 1
    
    void writeControl(int data) {
        envelope.write(data);
        lengthCounter.setHalt(envelope.isLoopEnabled());
    }
    
    void writePeriod(int data) {
        shortMode = (data & 0x80) != 0;
        int index = data & 0x0F;
        if (index < PERIOD_TABLE.length) {
            timerPeriod = PERIOD_TABLE[index];
        }
    }
    
    void writeLength(int data) {
        lengthCounter.load((data >> 3) & 0x1F);
        envelope.start();
    }
    
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            lengthCounter.clear();
        }
    }
    
    /**
     * Clock le timer du noise. Doit être appelé tous les 2 cycles CPU.
     */
    void clockTimer() {
        if (timerCounter == 0) {
            timerCounter = timerPeriod;
            clockShiftRegister();
        } else {
            timerCounter--;
        }
    }
    
    /**
     * Clock le shift register (LFSR)
     * BUG CORRIGÉ: Le feedback doit utiliser les bits AVANT le shift
     */
    private void clockShiftRegister() {
        // Récupérer le bit 0 (celui qui va sortir)
        int bit0 = shiftRegister & 0x01;
        
        // Sélectionner le bit de feedback selon le mode
        // Mode 0 (shortMode=false): bit 1 XOR bit 0 → sons graves, périodiques
        // Mode 1 (shortMode=true): bit 6 XOR bit 0 → sons aigus, pseudo-aléatoires
        int feedbackBit = shortMode ? 6 : 1;
        int otherBit = (shiftRegister >> feedbackBit) & 0x01;
        
        // Calculer le feedback
        int feedback = bit0 ^ otherBit;
        
        // Shifter à droite
        shiftRegister >>= 1;
        
        // Insérer le feedback au bit 14 (bit le plus significatif du LFSR 15-bit)
        shiftRegister = (shiftRegister & 0x3FFF) | (feedback << 14);
    }
    
    void quarterFrame() {
        envelope.clock();
    }
    
    void halfFrame() {
        lengthCounter.clock(enabled);
    }
    
    /**
     * Retourne le niveau de sortie du canal noise
     * BUG CORRIGÉ: Le bit 0 doit être testé APRÈS avoir vérifié les conditions
     */
    int output() {
        // Si désactivé ou length counter à 0, silence
        if (!enabled || !lengthCounter.isActive()) {
            return 0;
        }
        
        // Le bit 0 du shift register contrôle la sortie
        // Si bit 0 = 1 → silence (0)
        // Si bit 0 = 0 → sortie de l'enveloppe
        if ((shiftRegister & 0x01) != 0) {
            return 0;
        }
        
        return envelope.output();
    }
    
    boolean isActive() {
        return enabled && lengthCounter.isActive();
    }
    
    /**
     * Reset le canal (utile pour debug)
     */
    void reset() {
        enabled = false;
        shortMode = false;
        timerPeriod = PERIOD_TABLE[0];
        timerCounter = 0;
        shiftRegister = 1; // Important: initialiser à 1, pas 0
        envelope.reset();
        lengthCounter.clear();
    }
}