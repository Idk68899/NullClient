package dev.nullclient.ui;

/** One page of the menu. Widgets are added through the helpers on {@link NullScreen}. */
interface Tab {
    String title();

    void build(NullScreen ui, int x, int y, int w);

    /** Called before the screen is rebuilt, so typed text survives. */
    default void save() {
    }

    /** True while one of this tab's text boxes has focus. */
    default boolean typing() {
        return false;
    }

    /** Called about once a second while the tab is open. */
    default void refresh(NullScreen ui) {
    }
}