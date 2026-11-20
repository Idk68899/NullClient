package dev.nullclient.gui;

import java.util.ArrayList;
import java.util.List;

/** Shows what the server you are on runs, read from information it already sent you. */
final class PluginsPage implements Page {
    private PluginProbe.Report rep;
    private String note = "";

    @Override
    public String title() {
        return "Plugins";
    }

    @Override
    public int width() {
        return 330;
    }

    @Override
    public int height() {
        return 340;
    }

    private List<String> lines() {
        List<String> out = new ArrayList<>();
        if (rep == null) {
            return out;
        }
        out.add("server software: " + (rep.brand.isEmpty() ? "unknown" : rep.brand));
        out.add("version: " + (rep.version.isEmpty() ? "unknown" : rep.version));
        out.add("commands you can use: " + rep.roots);
        out.add("software hints: " + (rep.software.isEmpty() ? "none" : String.join(", ", rep.software)));
        out.add("plugins with their own commands: " + (rep.namespaces.isEmpty() ? "none found" : String.join(", ", rep.namespaces)));
        out.add("likely plugins:");
        for (String h : rep.hints) {
            out.add("  " + h);
        }
        List<String> listed = PluginProbe.plugins();
        if (!listed.isEmpty()) {
            out.add("/plugins answered: " + String.join(", ", listed));
        }
        return out;
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        if (rep == null || ui.frame % 40 == 0) {
            rep = PluginProbe.scan();
        }
        ui.text("Plugin probe - reads what the server already sent you", x, y, Theme.accent());
        y += 14;
        if (!rep.problem.isEmpty()) {
            ui.label(rep.problem, x, y, w, Theme.WARN);
            return;
        }
        int half = (w - 4) / 2;
        ui.button("Run /plugins", x, y, half, 18, () -> {
            PluginProbe.runPluginsCommand();
            note = "sent /plugins - answer shows below if the server allows it";
        });
        ui.button("Copy report", x + half + 4, y, half, 18, () -> {
            Ui.copy(String.join("\n", lines()));
            note = "report copied";
        });
        y += 24;
        String reply = PluginProbe.reply();
        ui.label(reply.isEmpty() ? note : reply, x, y, w, Theme.MUTED);
        y += 14;

        ui.label("server software: " + (rep.brand.isEmpty() ? "unknown" : rep.brand), x, y, w, Theme.TEXT);
        y += 12;
        ui.label("version: " + (rep.version.isEmpty() ? "unknown" : rep.version), x, y, w, Theme.TEXT);
        y += 12;
        ui.label("commands you can use: " + rep.roots, x, y, w, Theme.TEXT);
        y += 16;

        ui.text("Software hints", x, y, Theme.accent());
        y += 12;
        ui.label(rep.software.isEmpty() ? "none" : String.join(", ", rep.software), x, y, w, Theme.TEXT);
        y += 16;

        ui.text("Plugins that registered their own commands", x, y, Theme.accent());
        y += 12;
        if (rep.namespaces.isEmpty()) {
            ui.label("none found", x, y, w, Theme.MUTED);
            y += 12;
        }
        for (String n : rep.namespaces) {
            ui.label(n, x + 4, y, w - 4, Theme.TEXT);
            y += 11;
        }
        y += 4;

        ui.text("Likely plugins (guessed from command names)", x, y, Theme.accent());
        y += 12;
        if (rep.hints.isEmpty()) {
            ui.label("none recognised", x, y, w, Theme.MUTED);
            y += 12;
        }
        for (String hint : rep.hints) {
            ui.label(hint, x + 4, y, w - 4, Theme.TEXT);
            y += 11;
        }
        List<String> listed = PluginProbe.plugins();
        if (!listed.isEmpty()) {
            y += 6;
            ui.text("/plugins answered", x, y, Theme.accent());
            y += 12;
            ui.label(String.join(", ", listed), x + 4, y, w - 4, Theme.GOOD);
            y += 12;
        }
        y += 8;
        ui.label("The server only lists commands you may use, so hidden plugins can be missing.", x, y, w, Theme.MUTED);
    }
}