package dev.nullclient.gui;

/** One page of the menu, drawn every frame inside the content panel. */
interface Page {
    String title();

    void draw(Ui ui, int x, int y, int w, int h);
}