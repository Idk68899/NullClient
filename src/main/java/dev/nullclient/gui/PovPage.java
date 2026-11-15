package dev.nullclient.gui;

import dev.nullclient.NullClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.List;

/** Look through another player's eyes, and send a chat message or command. Servers and worlds only. */
final class PovPage implements Page {
    private final Field msg = new Field(200).hint("chat message or /command");
    private int pageIdx;
    private String note = "";

    PovPage() {
        msg.onEnter = this::send;
    }

    @Override
    public String title() {
        return "POV";
    }

    private void send() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        String t = msg.text.trim();
        if (conn == null || t.isEmpty()) {
            note = conn == null ? "not connected" : "type a message first";
            return;
        }
        if (t.startsWith("/")) {
            conn.sendCommand(t.substring(1));
        } else {
            conn.sendChat(t);
        }
        note = "sent as " + mc.getUser().getName();
        msg.text = "";
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            ui.text("Join a server or world first - POV works only while connected.", x, y, Theme.WARN);
            return;
        }
        NullClient nc = Ui.nc();
        ui.label(Pov.viewingOther() ? "Viewing: " + mc.getCameraEntity().getName().getString() : "Viewing: yourself", x, y, w, Theme.accent());
        y += 14;
        List<AbstractClientPlayer> players = new ArrayList<>(mc.level.players());
        players.remove(mc.player);
        int per = 4;
        int pages = Math.max(1, (players.size() + per - 1) / per);
        pageIdx = Math.max(0, Math.min(pageIdx, pages - 1));
        if (players.isEmpty()) {
            ui.text("No other players are loaded near you.", x, y, Theme.MUTED);
            y += 18;
        }
        for (int i = pageIdx * per; i < Math.min(players.size(), (pageIdx + 1) * per); i++) {
            AbstractClientPlayer p = players.get(i);
            String name = p.getName().getString();
            boolean mine = nc.store().isLocked() ? false : nc.profiles().byName(name).isPresent();
            ui.button((mine ? "[acct] " : "") + "View: " + name + "  (" + Math.round(p.distanceTo(mc.player)) + "m)", x, y, w, 18, () -> {
                Pov.view(p);
                note = "viewing " + name;
            });
            y += 22;
        }
        if (pages > 1) {
            ui.button("<", x, y, 22, 16, () -> pageIdx--);
            ui.text("page " + (pageIdx + 1) + "/" + pages, x + 28, y + 4, Theme.MUTED);
            ui.button(">", x + 90, y, 22, 16, () -> pageIdx++);
            y += 20;
        }
        ui.button("Back to me", x, y, w, 20, () -> {
            Pov.reset();
            note = "back to yourself";
        });
        y += 28;
        ui.text("Send as " + mc.getUser().getName() + ":", x, y, Theme.TEXT);
        y += 12;
        ui.field(msg, x, y, w - 54, 20);
        ui.button("Send", x + w - 50, y, 50, 20, this::send);
        y += 26;
        ui.label(note, x, y, w, Theme.MUTED);
    }
}