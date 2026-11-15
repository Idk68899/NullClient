package dev.nullclient.gui;

/** A text box's state. Drawn and edited by {@link Ui}. */
public final class Field {
    public String text = "";
    public final int max;
    public String hint = "";
    public boolean mask;
    public Runnable onEnter;

    public Field(int max) {
        this.max = max;
    }

    public Field hint(String h) {
        this.hint = h;
        return this;
    }

    /** Shows stars instead of the typed text (for passwords). */
    public Field masked() {
        this.mask = true;
        return this;
    }
}