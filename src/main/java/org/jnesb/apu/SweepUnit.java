package org.jnesb.apu;

final class SweepUnit {

    private final boolean negateOnesComplement;
    private boolean enabled;
    private int period;
    private boolean negate;
    private int shift;
    private int divider;
    private boolean reload;

    SweepUnit(boolean negateOnesComplement) {
        this.negateOnesComplement = negateOnesComplement;
    }

    void write(int data) {
        enabled = (data & 0x80) != 0;
        period = (data >> 4) & 0x07;
        negate = (data & 0x08) != 0;
        shift = data & 0x07;
        reload = true;
    }

    void clock(PulseChannel channel) {
        if (divider == 0 && enabled && shift > 0 && channel.timer() >= 8) {
            int change = channel.timer() >> shift;
            int target;
            if (negate) {
                int adjustment = negateOnesComplement ? 1 : 0;
                target = channel.timer() - change - adjustment;
            } else {
                target = channel.timer() + change;
            }
            if (target >= 0 && target < 0x800) {
                channel.setTimer(target);
            }
        }

        if (divider == 0 || reload) {
            divider = period;
            reload = false;
        } else {
            divider--;
        }
    }
}
