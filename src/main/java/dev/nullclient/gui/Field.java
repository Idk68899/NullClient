package dev.nullclient.gui;

/** A text box's state. Drawn and edited by {@link Ui}. */
public final class Field {
    public String text = "";
    public final int max;
    public String hint = "";
    public Runnable onEnter;

    public Field(int max) {
        this.max = max;
    }

    public Field hint(String h) {
        this.hint = h;
        return this;
    }
}