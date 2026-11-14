package dev.nullclient.gui;

import dev.nullclient.NullClient;
import dev.nullclient.core.session.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.ArrayList;
import java.util.List;

/** Offline accounts (just a username): add, select, delete, switch to, and join a server as. */
final class AccountsPage implements Page {
    private final Field name = new Field(24).hint("username");
    private final Field pass = new Field(128).hint("master password");
    private final Field ip = new Field(80).hint("server ip");
    private int pageIdx;
    private String note = "";

    @Override
    public String title() {
        return "Accounts";
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        NullClient c = Ui.nc();
        Minecraft mc = Minecraft.getInstance();
        ui.text("Playing as: " + mc.getUser().getName(), x, y, Theme.accent());
        y += 14;
        if (c.store().isLocked()) {
            ui.text("Profile store is locked. Enter the master password:", x, y, Theme.WARN);
            y += 14;
            ui.field(pass, x, y, w - 64, 20);
            ui.button("Unlock", x + w - 60, y, 60, 20, () -> {
                note = c.store().unlock(pass.text.toCharArray()) ? "unlocked" : "wrong password";
                pass.text = "";
            });
            y += 26;
            ui.label(note, x, y, w, Theme.MUTED);
            return;
        }
        List<Profile> list = new ArrayList<>(c.profiles().all());
        Profile active = c.profiles().active();
        int per = 4;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        pageIdx = Math.max(0, Math.min(pageIdx, pages - 1));
        if (list.isEmpty()) {
            ui.text("No accounts yet - add a username below.", x, y, Theme.MUTED);
            y += 18;
        }
        for (int i = pageIdx * per; i < Math.min(list.size(), (pageIdx + 1) * per); i++) {
            Profile p = list.get(i);
            ui.button((p == active ? "[x] " : "[ ] ") + p.name, x, y, w - 26, 18, () -> {
                c.profiles().setActive(p);
                note = "selected " + p.name;
            });
            ui.button("x", x + w - 22, y, 22, 18, () -> {
                c.profiles().delete(p);
                note = "deleted " + p.name;
            });
            y += 22;
        }
        if (pages > 1) {
            ui.button("<", x, y, 22, 16, () -> pageIdx--);
            ui.text("page " + (pageIdx + 1) + "/" + pages, x + 28, y + 4, Theme.MUTED);
            ui.button(">", x + 90, y, 22, 16, () -> pageIdx++);
            y += 20;
        }
        y += 4;
        ui.field(name, x, y, w - 132, 20);
        ui.button("Add", x + w - 128, y, 62, 20, () -> {
            try {
                Profile p = c.profiles().create(name.text);
                c.profiles().setActive(p);
                name.text = "";
                note = "added " + p.name;
            } catch (RuntimeException e) {
                note = String.valueOf(e.getMessage());
            }
        });
        ui.button("Random", x + w - 64, y, 64, 20, () -> {
            Profile p = c.profiles().generate();
            c.profiles().setActive(p);
            note = "generated " + p.name;
        });
        y += 28;
        ui.field(ip, x, y, w, 20);
        y += 24;
        int third = (w - 8) / 3;
        ui.button("Use current IP", x, y, third, 20, () -> {
            ServerData sd = mc.getCurrentServer();
            if (sd == null) {
                note = "you are not on a server right now";
            } else {
                ip.text = sd.ip;
                note = "ip set to " + sd.ip;
            }
        });
        ui.button("Switch account", x + third + 4, y, third, 20, () -> {
            Profile a = c.profiles().active();
            if (a == null) {
                note = "select an account first";
            } else {
                Join.switchAccount(a.name);
                note = "now playing as " + a.name + " (applies on your next join)";
            }
        });
        ui.button("Join", x + 2 * (third + 4), y, third, 20, () -> {
            try {
                Profile a = c.profiles().active();
                if (a == null) {
                    throw new IllegalArgumentException("select an account first");
                }
                Join.joinAs(a.name, ip.text.trim());
                note = "joining as " + a.name + "...";
            } catch (RuntimeException e) {
                note = String.valueOf(e.getMessage());
            }
        });
        y += 26;
        ui.label(note, x, y, w, Theme.MUTED);
    }
}