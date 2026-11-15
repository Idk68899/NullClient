package dev.nullclient.gui;

import java.util.Arrays;
import java.util.List;

/** Pictures for the menu (add by web link, or pick from files already in the folder), accent colour, opacity. */
final class SettingsPage implements Page {
    private static final String[] KEYS = {"background", "sidebar", "panel", "button"};
    private static final String[] NAMES = {"Background", "Sidebar", "Panels", "Buttons"};

    private final Field url = new Field(400).hint("direct link to a .png or .gif");
    private int target;
    private String note = "";

    @Override
    public String title() {
        return "Settings";
    }

    private void insert(String key) {
        List<String> files = Pics.list();
        if (files.isEmpty()) {
            note = "no images yet - download one with the link box";
            return;
        }
        int idx = files.indexOf(Theme.slot(key));
        String next = files.get((idx + 1) % files.size());
        Theme.setSlot(key, next);
        note = NAMES[Arrays.asList(KEYS).indexOf(key)] + " = " + next;
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        ui.text("Add a picture or GIF from a web link:", x, y, Theme.TEXT);
        y += 12;
        ui.field(url, x, y, w - 54, 20);
        ui.button("Paste", x + w - 50, y, 50, 20, () -> Ui.paste(url));
        y += 24;
        int half = (w - 4) / 2;
        ui.button("For: " + NAMES[target], x, y, half, 20, () -> target = (target + 1) % KEYS.length);
        ui.button("Download", x + half + 4, y, half, 20, () -> Pics.download(url.text, KEYS[target]));
        y += 24;
        String st = Pics.status();
        ui.label(st.isEmpty() ? "Use a direct image link, e.g. https://i.imgur.com/xxxx.png" : st, x, y, w,
                st.startsWith("download failed") ? Theme.BAD : Theme.MUTED);
        y += 16;
        for (int i = 0; i < KEYS.length; i++) {
            String key = KEYS[i];
            String cur = Theme.slot(key);
            ui.label(NAMES[i] + ": " + (cur.isEmpty() ? "none" : cur), x, y + 5, w - 128, Theme.TEXT);
            ui.button("Next", x + w - 124, y, 60, 18, () -> insert(key));
            ui.button("Clear", x + w - 60, y, 60, 18, () -> Theme.setSlot(key, ""));
            y += 22;
        }
        y += 4;
        int third = (w - 8) / 3;
        ui.button("Reload images", x, y, third, 20, () -> {
            Pics.reload();
            note = "found " + Pics.list().size() + " image(s)";
        });
        ui.button("Accent: " + Theme.accentName(), x + third + 4, y, third, 20, Theme::nextAccent);
        ui.button("Panels: " + Theme.opacityName(), x + 2 * (third + 4), y, third, 20, Theme::nextOpacity);
        y += 26;
        ui.label(note, x, y, w, Theme.MUTED);
        y += 14;
        String err = Pics.error();
        if (!err.isEmpty()) {
            ui.label("error: " + err, x, y, w, Theme.BAD);
        }
    }
}