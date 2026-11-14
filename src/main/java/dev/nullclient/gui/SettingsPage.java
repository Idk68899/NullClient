package dev.nullclient.gui;

import java.util.List;

/** Pictures for the menu (PNG or GIF from config/nullclient/ui/), accent colour and panel opacity. */
final class SettingsPage implements Page {
    private static final String[] KEYS = {"background", "sidebar", "panel", "button"};
    private static final String[] NAMES = {"Background", "Sidebar", "Panels", "Buttons"};
    private String note = "";

    @Override
    public String title() {
        return "Settings";
    }

    private void insert(String key) {
        List<String> files = Pics.list();
        if (files.isEmpty()) {
            note = "no PNG/GIF files in config/nullclient/ui yet";
            return;
        }
        int idx = files.indexOf(Theme.slot(key));
        String next = files.get((idx + 1) % files.size());
        Theme.setSlot(key, next);
        note = NAMES[java.util.Arrays.asList(KEYS).indexOf(key)] + " = " + next;
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        ui.label("Put PNG or GIF files in config/nullclient/ui/ then press Reload.", x, y, w, Theme.MUTED);
        y += 16;
        for (int i = 0; i < KEYS.length; i++) {
            String key = KEYS[i];
            String cur = Theme.slot(key);
            ui.label(NAMES[i] + ": " + (cur.isEmpty() ? "none" : cur), x, y + 5, w - 128, Theme.TEXT);
            ui.button("Insert", x + w - 124, y, 60, 18, () -> insert(key));
            ui.button("Clear", x + w - 60, y, 60, 18, () -> Theme.setSlot(key, ""));
            y += 22;
        }
        y += 6;
        int third = (w - 8) / 3;
        ui.button("Reload images", x, y, third, 20, () -> {
            Pics.reload();
            note = "found " + Pics.list().size() + " file(s)";
        });
        ui.button("Accent: " + Theme.accentName(), x + third + 4, y, third, 20, Theme::nextAccent);
        ui.button("Panels: " + Theme.opacityName(), x + 2 * (third + 4), y, third, 20, Theme::nextOpacity);
        y += 28;
        ui.label(note, x, y, w, Theme.MUTED);
        y += 14;
        String err = Pics.error();
        if (!err.isEmpty()) {
            ui.label("error: " + err, x, y, w, Theme.BAD);
        }
    }
}