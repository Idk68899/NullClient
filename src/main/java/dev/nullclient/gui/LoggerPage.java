package dev.nullclient.gui;

import dev.nullclient.ai.RunLog;

import java.util.ArrayList;
import java.util.List;

/** The AI's recent log lines (newest first), with a button that copies the latest ones. */
final class LoggerPage implements Page {
    private String note = "";

    @Override
    public String title() {
        return "Logger";
    }

    @Override
    public int width() {
        return 330;
    }

    @Override
    public int height() {
        return 240;
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        List<String> lines = new ArrayList<>(RunLog.recent(80));
        ui.button("Copy last 25 lines", x, y, w, 18, () -> {
            List<String> tail = lines.subList(Math.max(0, lines.size() - 25), lines.size());
            Ui.copy(String.join("\n", tail));
            note = "copied " + tail.size() + " lines";
        });
        y += 22;
        if (!note.isEmpty()) {
            ui.label(note, x, y, w, Theme.MUTED);
            y += 12;
        }
        if (lines.isEmpty()) {
            ui.text("Nothing logged yet - run an AI task first.", x, y, Theme.MUTED);
            return;
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i);
            String low = l.toLowerCase();
            int color = low.contains("fail") || low.contains("stuck") || low.contains("cannot") ? Theme.BAD
                    : low.contains("killed") || low.contains("done") || low.contains("succeeded") ? Theme.GOOD : Theme.TEXT;
            ui.label(l, x, y, w, color);
            y += 11;
        }
    }
}