package dev.nullclient.ai.nav;

public enum MoveType {
    START, WALK, DIAGONAL, STEP_UP, DESCEND, PARKOUR;

    public boolean isFlat() {
        return this == WALK || this == DIAGONAL;
    }
}