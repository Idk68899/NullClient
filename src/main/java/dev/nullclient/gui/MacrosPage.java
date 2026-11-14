package dev.nullclient.gui;

import dev.nullclient.core.macro.Macro;
import dev.nullclient.core.macro.MacroManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Record, play and delete movement macros. */
final class MacrosPage implements Page {
    private final Field name = new Field(32).hint("macro name");
    private int pageIdx;
    private String note = "";

    @Override
    public String title() {
        return "Macros";
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        MacroManager m = Ui.nc().macros();
        ui.field(name, x, y, w - 124, 20);
        if (m.isRecording()) {
            ui.button("Stop & save", x + w - 120, y, 120, 20, () -> {
                m.stopRecording(true);
                note = "saved";
            });
        } else {
            ui.button("Record", x + w - 120, y, 120, 20, () -> {
                note = m.startRecording(name.text) ? "recording - close this menu and move" : "need a name, and nothing else running";
            });
        }
        y += 26;
        if (m.isPlaying()) {
            ui.button("Stop playback", x, y, w, 20, m::stopPlayback);
            y += 24;
        }
        List<Macro> list = new ArrayList<>(m.all());
        int per = 5;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        pageIdx = Math.max(0, Math.min(pageIdx, pages - 1));
        if (list.isEmpty()) {
            ui.text("No macros yet.", x, y, Theme.MUTED);
            y += 18;
        }
        for (int i = pageIdx * per; i < Math.min(list.size(), (pageIdx + 1) * per); i++) {
            Macro mac = list.get(i);
            ui.button(String.format(Locale.ROOT, "Play: %s (%.1fs)", mac.name, mac.length / 20.0), x, y, w - 26, 18, () ->
                    note = m.play(mac.name, false) ? "playing " + mac.name : "cannot play right now");
            ui.button("x", x + w - 22, y, 22, 18, () -> m.delete(mac.name));
            y += 22;
        }
        if (pages > 1) {
            ui.button("<", x, y, 22, 16, () -> pageIdx--);
            ui.text("page " + (pageIdx + 1) + "/" + pages, x + 28, y + 4, Theme.MUTED);
            ui.button(">", x + 90, y, 22, 16, () -> pageIdx++);
            y += 20;
        }
        ui.label(note, x, y + 4, w, Theme.MUTED);
    }
}