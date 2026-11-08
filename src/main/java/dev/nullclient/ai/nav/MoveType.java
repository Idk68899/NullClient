package dev.nullclient.ai.nav;

public enum MoveType {
    START, WALK, DIAGONAL, STEP_UP, DESCEND, PARKOUR, DIG_WALK, DIG_DOWN, DIG_UP, SWIM, PILLAR;

    public boolean isFlat() {
        return this == WALK || this == DIAGONAL;
    }

    /** Moves that first require breaking blocks. */
    public boolean isDig() {
        return this == DIG_WALK || this == DIG_DOWN || this == DIG_UP;
    }
}