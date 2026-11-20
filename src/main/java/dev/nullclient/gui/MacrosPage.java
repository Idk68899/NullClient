
package dev.nullclient.gui;

import dev.nullclient.core.macro.Macro;
import dev.nullclient.core.macro.MacroManager;
import dev.nullclient.core.script.Script;
import dev.nullclient.core.script.ScriptRunner;
import dev.nullclient.core.script.ScriptStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Recorded macros (record your movement and replay it) and Scripts (build a sequence of actions from a palette). */
final class MacrosPage implements Page {
    private static final String[] MODES = {"Recorded", "Scripts"};

    private final Field recName = new Field(32).hint("macro name");
    private final Field newScript = new Field(32).hint("new script name");
    private final Field repeat = new Field(5).hint("1");
    private final Field valueF = new Field(8).hint("value");
    private final Field textF = new Field(120).hint("text");

    private int mode;
    private Script editing;
    private String kindSel = "walk";
    private int selStep = -1;
    private String note = "";

    MacrosPage() {
        valueF.text = "2";
    }

    @Override
    public String title() {
        return "Macros";
    }

    @Override
    public int width() {
        return 340;
    }

    @Override
    public int height() {
        return 400;
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        int half = (w - 4) / 2;
        for (int i = 0; i < MODES.length; i++) {
            int m = i;
            ui.button((i == mode ? "> " : "") + MODES[i], x + i * (half + 4), y, half, 18, () -> mode = m);
        }
        y += 24;
        if (mode == 0) {
            recorded(ui, x, y, w);
        } else if (editing == null) {
            scripts(ui, x, y, w);
        } else {
            editor(ui, x, y, w);
        }
    }

    // ------------------------------------------------------------------ recorded macros

    private void recorded(Ui ui, int x, int y, int w) {
        MacroManager m = Ui.nc().macros();
        ui.field(recName, x, y, w - 124, 20);
        if (m.isRecording()) {
            ui.button("Stop & save", x + w - 120, y, 120, 20, () -> {
                m.stopRecording(true);
                note = "saved";
            });
        } else {
            ui.button("Record", x + w - 120, y, 120, 20, () ->
                    note = m.startRecording(recName.text) ? "recording - close this menu and move" : "need a name, and nothing else running");
        }
        y += 26;
        if (m.isPlaying()) {
            ui.button("Stop playback", x, y, w, 20, m::stopPlayback);
            y += 24;
        }
        List<Macro> list = new ArrayList<>(m.all());
        if (list.isEmpty()) {
            ui.text("No recorded macros yet.", x, y, Theme.MUTED);
            y += 18;
        }
        for (Macro mac : list) {
            ui.button(String.format(Locale.ROOT, "Play: %s (%.1fs)", mac.name, mac.length / 20.0), x, y, w - 26, 18, () ->
                    note = m.play(mac.name, false) ? "playing " + mac.name : "cannot play right now");
            ui.button("x", x + w - 22, y, 22, 18, () -> m.delete(mac.name));
            y += 22;
        }
        ui.label(note, x, y + 4, w, Theme.MUTED);
    }

    // ------------------------------------------------------------------ script list

    private void open(Script sc) {
        editing = sc;
        selStep = -1;
        repeat.text = Integer.toString(sc.repeat);
        note = "";
    }

    private void scripts(Ui ui, int x, int y, int w) {
        ui.label("Scripts: ordered actions - movement, clicks, AI and client actions.", x, y, w, Theme.MUTED);
        y += 14;
        ui.field(newScript, x, y, w - 74, 20);
        ui.button("Create", x + w - 70, y, 70, 20, () -> {
            try {
                Script sc = ScriptStore.add(newScript.text);
                newScript.text = "";
                open(sc);
            } catch (IllegalArgumentException e) {
                note = e.getMessage();
            }
        });
        y += 26;
        List<Script> list = new ArrayList<>(ScriptStore.all());
        if (list.isEmpty()) {
            ui.text("No scripts yet - create one above.", x, y, Theme.MUTED);
            y += 18;
        }
        for (Script sc : list) {
            ui.button(sc.name + "  (" + sc.steps.size() + " steps)", x, y, w - 88, 18, () -> open(sc));
            ui.button("Run", x + w - 84, y, 40, 18, () -> {
                ScriptRunner.start(sc, sc.repeat);
                note = ScriptRunner.status();
            });
            ui.button("x", x + w - 40, y, 40, 18, () -> ScriptStore.remove(sc));
            y += 22;
        }
        y += 4;
        if (ScriptRunner.running()) {
            ui.button("Stop running script", x, y, w, 18, ScriptRunner::stop);
            y += 22;
        }
        ui.label(ScriptRunner.running() ? ScriptRunner.status() : note.isEmpty() ? ScriptRunner.status() : note, x, y, w,
                ScriptRunner.running() ? Theme.GOOD : Theme.MUTED);
    }

    // ------------------------------------------------------------------ script editor

    private void pickKind(Script.Kind k) {
        kindSel = k.id();
        valueF.text = k.hasValue() ? Script.describe(new Script.Step(k.id(), k.def(), "")).replaceAll("^.*?[ ](-?[0-9.]+).*$", "$1") : "";
        if (k.hasValue()) {
            valueF.text = k.def() == Math.rint(k.def()) ? Long.toString((long) k.def()) : Double.toString(k.def());
        }
        textF.hint = k.needsText() ? k.textHint() : "";
        textF.text = "";
    }

    private Script.Step build() {
        Script.Kind k = Script.kind(kindSel);
        if (k == null) {
            throw new IllegalArgumentException("pick an action first");
        }
        double v = 0.0;
        if (k.hasValue()) {
            try {
                v = Double.parseDouble(valueF.text.trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("the value must be a number");
            }
        }
        String err = k.validate(v, textF.text);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        return new Script.Step(k.id(), v, k.needsText() ? textF.text.trim() : "");
    }

    private void editor(Ui ui, int x, int y, int w) {
        Script sc = editing;
        int half = (w - 4) / 2;
        ui.button("< All scripts", x, y, half, 18, () -> {
            editing = null;
            selStep = -1;
        });
        ui.label(sc.name + "  (" + sc.steps.size() + " steps)", x + half + 8, y + 5, half - 8, Theme.accent());
        y += 24;

        ui.label("Repeat (0 = forever):", x, y + 5, 118, Theme.TEXT);
        ui.field(repeat, x + 122, y, 40, 20);
        boolean running = ScriptRunner.running();
        ui.button(running ? "Stop" : "Run", x + 168, y, w - 168, 20, () -> {
            if (running) {
                ScriptRunner.stop();
                return;
            }
            int n = 1;
            try {
                n = repeat.text.isBlank() ? 1 : Integer.parseInt(repeat.text.trim());
            } catch (NumberFormatException e) {
                note = "repeat must be a whole number";
                return;
            }
            sc.repeat = Math.max(0, Math.min(9999, n));
            ScriptStore.save();
            ScriptRunner.start(sc, sc.repeat);
            note = "";
        });
        y += 24;
        ui.label(running ? ScriptRunner.status() : note.isEmpty() ? ScriptRunner.status() : note, x, y, w,
                running ? Theme.GOOD : Theme.MUTED);
        y += 16;

        ui.text("Steps", x, y, Theme.accent());
        y += 12;
        if (sc.steps.isEmpty()) {
            ui.text("empty - add actions below", x, y, Theme.MUTED);
            y += 14;
        }
        int active = ScriptRunner.running() ? ScriptRunner.index() : -1;
        for (int i = 0; i < sc.steps.size(); i++) {
            int idx = i;
            Script.Step st = sc.steps.get(i);
            ui.button((i == selStep ? "> " : "") + (i + 1) + ". " + Script.describe(st), x, y, w - 74, 18, () -> {
                selStep = idx;
                Script.Kind k = Script.kind(st.kind);
                if (k != null) {
                    kindSel = k.id();
                    valueF.text = st.value == Math.rint(st.value) ? Long.toString((long) st.value) : Double.toString(st.value);
                    textF.text = st.text;
                    textF.hint = k.needsText() ? k.textHint() : "";
                }
            });
            if (i == active) {
                ui.fill(x, y, 3, 18, Theme.GOOD);
            }
            ui.button("^", x + w - 72, y, 22, 18, () -> {
                if (idx > 0) {
                    sc.steps.add(idx - 1, sc.steps.remove(idx));
                    selStep = idx - 1;
                    ScriptStore.save();
                }
            });
            ui.button("v", x + w - 48, y, 22, 18, () -> {
                if (idx < sc.steps.size() - 1) {
                    sc.steps.add(idx + 1, sc.steps.remove(idx));
                    selStep = idx + 1;
                    ScriptStore.save();
                }
            });
            ui.button("x", x + w - 24, y, 22, 18, () -> {
                sc.steps.remove(idx);
                selStep = -1;
                ScriptStore.save();
            });
            y += 22;
        }
        y += 6;

        for (String group : new String[]{"game", "client"}) {
            ui.text(group.equals("game") ? "Game actions" : "Client actions", x, y, Theme.accent());
            y += 12;
            int col = 0;
            for (Script.Kind k : Script.KINDS) {
                if (!k.group().equals(group)) {
                    continue;
                }
                ui.button((k.id().equals(kindSel) ? "> " : "") + k.label(), x + col * (half + 4), y, half, 18, () -> pickKind(k));
                if (col == 1) {
                    y += 22;
                }
                col = 1 - col;
            }
            if (col == 1) {
                y += 22;
            }
            y += 4;
        }

        Script.Kind k = Script.kind(kindSel);
        if (k != null) {
            ui.label("Selected: " + k.label() + " - " + k.hint(), x, y, w, Theme.MUTED);
            y += 14;
            if (k.hasValue()) {
                String unit = switch (k.unit()) {
                    case "s" -> "seconds";
                    case "deg" -> "degrees";
                    case "slot" -> "slot 1-9";
                    default -> "times";
                };
                ui.label("Value (" + unit + "):", x, y + 5, 118, Theme.TEXT);
                ui.field(valueF, x + 122, y, w - 122, 20);
                y += 24;
            }
            if (k.needsText()) {
                ui.field(textF, x, y, w, 20);
                y += 24;
            }
            ui.button("Add step", x, y, half, 20, () -> {
                try {
                    sc.steps.add(build());
                    selStep = -1;
                    ScriptStore.save();
                    note = "added";
                } catch (IllegalArgumentException e) {
                    note = e.getMessage();
                }
            });
            ui.button("Update selected", x + half + 4, y, half, 20, () -> {
                if (selStep < 0 || selStep >= sc.steps.size()) {
                    note = "tap a step first";
                    return;
                }
                try {
                    sc.steps.set(selStep, build());
                    ScriptStore.save();
                    note = "updated step " + (selStep + 1);
                } catch (IllegalArgumentException e) {
                    note = e.getMessage();
                }
            });
        }
    }
}