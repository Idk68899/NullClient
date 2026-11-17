package dev.nullclient.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/** Info about the server (or world) you are in right now. */
final class ServerPage implements Page {
    private String note = "";

    @Override
    public String title() {
        return "Server Info";
    }

    @Override
    public int width() {
        return 300;
    }

    @Override
    public int height() {
        return 190;
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        ui.label("Playing as: " + mc.getUser().getName(), x, y, w, Theme.accent());
        y += 14;
        if (mc.level == null) {
            ui.text("Not in a world or server.", x, y, Theme.MUTED);
            return;
        }
        ServerData sd = mc.getCurrentServer();
        if (sd == null) {
            ui.text("Singleplayer or LAN (no saved server entry).", x, y, Theme.MUTED);
            y += 14;
        } else {
            ui.label("Name: " + sd.name, x, y, w, Theme.TEXT);
            y += 12;
            ui.label("Address: " + sd.ip, x, y, w - 70, Theme.TEXT);
            ui.button("Copy IP", x + w - 66, y - 3, 66, 14, () -> {
                Ui.copy(sd.ip);
                note = "address copied";
            });
            y += 14;
            ui.label("Ping: " + sd.ping + " ms", x, y, w, Theme.TEXT);
            y += 12;
            ui.label("Version: " + (sd.version == null ? "?" : sd.version.getString()), x, y, w, Theme.TEXT);
            y += 12;
            ui.label("MOTD: " + (sd.motd == null ? "-" : sd.motd.getString().replace('\n', ' ')), x, y, w, Theme.MUTED);
            y += 14;
        }
        ui.label("Players loaded near you: " + mc.level.players().size(), x, y, w, Theme.TEXT);
        y += 14;
        ui.label(note, x, y, w, Theme.MUTED);
    }
}