package dev.nullclient.gui;

/** One page of the menu, drawn every frame inside its window. */
interface Page {
    String title();

    void draw(Ui ui, int x, int y, int w, int h);

    /** Default window size (cut down to fit small screens). */
    default int width() {
        return 300;
    }

    default int height() {
        return 240;
    }
}