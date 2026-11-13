package dev.nullclient.ui;

import dev.nullclient.core.macro.Macro;
import dev.nullclient.core.macro.MacroManager;
import net.minecraft.client.gui.components.EditBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Record, play and delete movement macros. */
final class MacrosTab implements Tab {
    private static String macroText = "";
    private static String note = "";

    private EditBox macroBox;

    @Override
    public String title() {
        return "Macros";
    }

    @Override
    public void build(NullScreen ui, int x, int y, int w) {
        MacroManager m = NullScreen.nc().macros();
        macroBox = ui.box(macroText, x, y, w - 124, "macro name", 32);
        if (m.isRecording()) {
            ui.button("Stop & save", x + w - 120, y, 120, () -> {
                m.stopRecording(true);
                note = "saved";
                ui.reopen();
            });
        } else {
            ui.button("Record", x + w - 120, y, 120, () -> {
                boolean ok = m.startRecording(macroBox.getValue());
                note = ok ? "recording - close this menu and move" : "need a name, and nothing else running";
                ui.reopen();
            });
        }
        y += 26;
        if (m.isPlaying()) {
            ui.button("Stop playback", x, y, w, () -> {
                m.stopPlayback();
                ui.reopen();
            });
            y += 24;
        }
        List<Macro> list = new ArrayList<>(m.all());
        int shown = Math.min(list.size(), 7);
        if (list.isEmpty()) {
            ui.label("No macros yet.", x, y, w);
            y += 22;
        }
        for (int i = 0; i < shown; i++) {
            Macro mac = list.get(i);
            ui.button(String.format(Locale.ROOT, "Play: %s (%.1fs)", mac.name, mac.length / 20.0), x, y, w - 26, () -> {
                note = m.play(mac.name, false) ? "playing " + mac.name : "cannot play right now";
                ui.reopen();
            });
            ui.button("x", x + w - 22, y, 22, () -> {
                m.delete(mac.name);
                ui.reopen();
            });
            y += 22;
        }
        y += 6;
        ui.label(note, x, y, w);
    }

    @Override
    public void save() {
        if (macroBox != null) {
            macroText = macroBox.getValue();
        }
    }

    @Override
    public boolean typing() {
        return macroBox != null && macroBox.isFocused();
    }
}