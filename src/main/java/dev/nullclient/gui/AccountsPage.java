package dev.nullclient.gui;

import dev.nullclient.NullClient;
import dev.nullclient.core.session.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

/** Offline accounts: manage them, save a server password for auto login, and join a server as one. */
final class AccountsPage implements Page {
    private static final String[] MODES = {"Accounts", "Auto login", "Join"};

    private final Field name = new Field(24).hint("username");
    private final Field unlock = new Field(128).hint("master password").masked();
    private final Field master = new Field(128).hint("new master password (8+)").masked();
    private final Field acctPass = new Field(64).hint("server password").masked();
    private final Field ip = new Field(80).hint("server ip");
    private int mode;
    private int pageIdx;
    private String note = "";
    private String syncedId = "";

    @Override
    public String title() {
        return "Accounts";
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        NullClient c = Ui.nc();
        Minecraft mc = Minecraft.getInstance();
        ui.label("Playing as: " + mc.getUser().getName(), x, y, w, Theme.accent());
        y += 14;
        int third = (w - 8) / 3;
        for (int i = 0; i < MODES.length; i++) {
            int m = i;
            ui.button((i == mode ? "> " : "") + MODES[i], x + i * (third + 4), y, third, 18, () -> mode = m);
        }
        y += 24;
        if (c.store().isLocked()) {
            ui.text("Profile store is locked. Enter the master password:", x, y, Theme.WARN);
            y += 14;
            ui.field(unlock, x, y, w - 64, 20);
            ui.button("Unlock", x + w - 60, y, 60, 20, () -> {
                note = c.store().unlock(unlock.text.toCharArray()) ? "unlocked" : "wrong password";
                unlock.text = "";
            });
            y += 26;
            ui.label(note, x, y, w, Theme.MUTED);
            return;
        }
        switch (mode) {
            case 0 -> accounts(ui, c, x, y, w);
            case 1 -> autoLogin(ui, c, x, y, w);
            default -> join(ui, c, mc, x, y, w);
        }
    }

    private void accounts(Ui ui, NullClient c, int x, int y, int w) {
        List<Profile> list = new ArrayList<>(c.profiles().all());
        Profile active = c.profiles().active();
        int per = 3;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        pageIdx = Math.max(0, Math.min(pageIdx, pages - 1));
        if (list.isEmpty()) {
            ui.text("No accounts yet - add a username below.", x, y, Theme.MUTED);
            y += 18;
        }
        for (int i = pageIdx * per; i < Math.min(list.size(), (pageIdx + 1) * per); i++) {
            Profile p = list.get(i);
            ui.button((p == active ? "[x] " : "[ ] ") + p.name + (p.autoLogin ? "  (auto login)" : ""), x, y, w - 26, 18, () -> {
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
        ui.label(note, x, y, w, Theme.MUTED);
    }

    private void autoLogin(Ui ui, NullClient c, int x, int y, int w) {
        Profile cur = c.profiles().active();
        if (cur == null) {
            ui.text("Select an account first (Accounts view).", x, y, Theme.MUTED);
            return;
        }
        if (!cur.id.equals(syncedId)) {
            acctPass.text = cur.password;
            syncedId = cur.id;
        }
        ui.text("Server password for " + cur.name + ":", x, y, Theme.TEXT);
        y += 12;
        ui.field(acctPass, x, y, w - 54, 20);
        ui.button("Paste", x + w - 50, y, 50, 20, () -> Ui.paste(acctPass));
        y += 24;
        int third = (w - 8) / 3;
        ui.button("Save", x, y, third, 20, () -> {
            cur.password = acctPass.text;
            c.store().save();
            note = "saved";
        });
        ui.button("Auto: " + (cur.autoLogin ? "ON" : "OFF"), x + third + 4, y, third, 20, () -> {
            cur.autoLogin = !cur.autoLogin;
            c.store().save();
            note = cur.autoLogin ? "will type /login or /register for you" : "you will get a popup instead";
        });
        ui.button("Clear", x + 2 * (third + 4), y, third, 20, () -> {
            cur.password = "";
            cur.autoLogin = false;
            acctPass.text = "";
            c.store().save();
            note = "cleared";
        });
        y += 26;
        ui.label(c.store().isProtected() ? "Stored encrypted (master password set)."
                : "Stored as plain text - set a master password below.", x, y, w,
                c.store().isProtected() ? Theme.GOOD : Theme.WARN);
        y += 14;
        if (!c.store().isProtected()) {
            ui.field(master, x, y, w - 74, 20);
            ui.button("Encrypt", x + w - 70, y, 70, 20, () -> {
                if (master.text.length() < 8) {
                    note = "master password needs 8+ characters";
                    return;
                }
                try {
                    c.store().setMasterPassword(master.text.toCharArray());
                    master.text = "";
                    note = "store encrypted";
                } catch (GeneralSecurityException e) {
                    note = "encryption failed: " + e.getMessage();
                }
            });
            y += 24;
        }
        ui.label(note, x, y, w, Theme.MUTED);
    }

    private void join(Ui ui, NullClient c, Minecraft mc, int x, int y, int w) {
        ui.field(ip, x, y, w - 54, 20);
        ui.button("Paste", x + w - 50, y, 50, 20, () -> Ui.paste(ip));
        y += 26;
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