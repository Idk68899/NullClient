package dev.nullclient.gui;

import dev.nullclient.NullClient;
import dev.nullclient.core.session.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Account manager. Accounts tab: add/generate/search/login/edit/delete offline accounts in a scrollable list.
 * Setup tab: saved servers with a name, address, Use Current and Join. Login tab: server password and auto login
 * for the selected account.
 */
final class AccountsPage implements Page {
    private static final String[] TABS = {"Accounts", "Setup", "Login"};
    private static final String[] ADD = {"Cracked", "Generated"};
    private static final int ROW_H = 22;

    private final Field name = new Field(24).hint("Username");
    private final Field search = new Field(24).hint("Search nickname...");
    private final Field unlock = new Field(128).hint("master password").masked();
    private final Field master = new Field(128).hint("new master password (8+)").masked();
    private final Field acctPass = new Field(64).hint("server password").masked();
    private final Field sName = new Field(40).hint("profile name");
    private final Field sIp = new Field(80).hint("server ip");

    private int tab;
    private int addMode;
    private double scroll;
    private Profile editing;
    private boolean armedClear;
    private Servers.Entry sel;
    private String syncedPass = "";
    private String note = "";

    @Override
    public String title() {
        return "Accounts";
    }

    @Override
    public int width() {
        return 360;
    }

    @Override
    public int height() {
        return 290;
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        NullClient c = Ui.nc();
        Minecraft mc = Minecraft.getInstance();
        int tw = (w - 8) / 3;
        for (int i = 0; i < TABS.length; i++) {
            int t = i;
            ui.button((i == tab ? "> " : "") + TABS[i], x + i * (tw + 4), y, tw, 18, () -> {
                tab = t;
                armedClear = false;
            });
        }
        y += 24;
        h -= 24;
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
        switch (tab) {
            case 0 -> accounts(ui, c, mc, x, y, w, h);
            case 1 -> setup(ui, c, mc, x, y, w, h);
            default -> login(ui, c, x, y, w);
        }
    }

    // ------------------------------------------------------------------ Accounts

    private void addOrSave(NullClient c) {
        try {
            if (editing != null) {
                c.profiles().rename(editing, name.text);
                note = "renamed to " + editing.name;
                editing = null;
            } else {
                Profile p = c.profiles().create(name.text);
                p.kind = "cracked";
                c.store().save();
                c.profiles().setActive(p);
                note = "added " + p.name;
            }
            name.text = "";
        } catch (RuntimeException e) {
            note = String.valueOf(e.getMessage());
        }
    }

    private void accounts(Ui ui, NullClient c, Minecraft mc, int x, int y, int w, int h) {
        int bottom = y + h;
        int half = (w - 4) / 2;
        for (int i = 0; i < ADD.length; i++) {
            int m = i;
            ui.button(i == addMode ? "[" + ADD[i] + "]" : ADD[i], x + i * (half + 4), y, half, 18, () -> addMode = m);
        }
        y += 24;
        if (addMode == 0) {
            ui.field(name, x, y, w, 20);
            y += 24;
            ui.button(editing == null ? "Add Cracked" : "Save", x, y, half, 20, () -> addOrSave(c));
            ui.button(editing == null ? "Clear" : "Cancel", x + half + 4, y, half, 20, () -> {
                name.text = "";
                editing = null;
            });
            y += 24;
        } else {
            ui.label("Makes a random username like Silent_Falcon_42.", x, y, w, Theme.MUTED);
            y += 14;
            ui.button("Generate one", x, y, w, 20, () -> {
                Profile p = c.profiles().generate();
                p.kind = "generated";
                c.store().save();
                c.profiles().setActive(p);
                note = "generated " + p.name;
            });
            y += 24;
        }
        ui.field(search, x, y, w, 20);
        y += 26;

        String q = search.text.trim().toLowerCase(Locale.ROOT);
        List<Profile> list = new ArrayList<>();
        for (Profile p : c.profiles().all()) {
            if (q.isEmpty() || p.name.toLowerCase(Locale.ROOT).contains(q)) {
                list.add(p);
            }
        }

        ui.label("Accounts  (" + list.size() + ")", x, y + 4, w - 150, Theme.TEXT);
        ui.button(armedClear ? "Sure?" : "Clear List", x + w - 148, y, 72, 18, () -> {
            if (!armedClear) {
                armedClear = true;
                note = "tap Clear List again to delete every account";
            } else {
                for (Profile p : new ArrayList<>(c.profiles().all())) {
                    c.profiles().delete(p);
                }
                armedClear = false;
                note = "all accounts deleted";
            }
        });
        ui.button("Generate", x + w - 72, y, 72, 18, () -> {
            Profile p = c.profiles().generate();
            p.kind = "generated";
            c.store().save();
            note = "generated " + p.name;
        });
        y += 22;

        int listTop = y;
        int listH = Math.max(ROW_H, bottom - y - 20);
        int contentH = list.size() * ROW_H;
        int maxScroll = Math.max(0, contentH - listH);
        if (ui.hover(x, listTop, w, listH)) {
            scroll -= ui.scrollDelta() * ROW_H * 2;
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        boolean needBar = contentH > listH;
        int barW = needBar ? 6 : 0;
        int rowW = w - barW - 2;

        ui.clip(x, listTop, w, listH);
        String playing = mc.getUser().getName();
        Profile active = c.profiles().active();
        int btnArea = 46 + 4 + 20 + 4 + 20;
        int typeW = 64;
        int ry = listTop - (int) scroll;
        for (Profile p : list) {
            if (ry + ROW_H >= listTop && ry < listTop + listH) {
                boolean current = p.name.equals(playing);
                if (current) {
                    ui.fill(x, ry, rowW, 20, 0x4433FF99);
                } else if (p == active) {
                    ui.fill(x, ry, rowW, 20, 0x33FFFFFF);
                }
                ui.label(p.name, x + 4, ry + 6, rowW - btnArea - typeW - 12, current ? Theme.GOOD : Theme.TEXT);
                ui.label("generated".equals(p.kind) ? "Generated" : "Cracked", x + rowW - btnArea - typeW, ry + 6, typeW, Theme.MUTED);
                int bx = x + rowW - btnArea;
                if (current) {
                    ui.button("Active", bx, ry + 1, 46, 18, () -> note = "this is the account you are playing as");
                } else {
                    ui.button("Login", bx, ry + 1, 46, 18, () -> {
                        c.profiles().setActive(p);
                        Join.switchAccount(p.name);
                        note = "now playing as " + p.name + " (applies on your next join)";
                    });
                }
                ui.button("e", bx + 50, ry + 1, 20, 18, () -> {
                    editing = p;
                    addMode = 0;
                    name.text = p.name;
                    note = "editing " + p.name;
                });
                ui.button("x", bx + 74, ry + 1, 20, 18, () -> {
                    c.profiles().delete(p);
                    if (editing == p) {
                        editing = null;
                    }
                    note = "deleted " + p.name;
                });
            }
            ry += ROW_H;
        }
        if (list.isEmpty()) {
            ui.text("No accounts yet - add a username above.", x, listTop + 2, Theme.MUTED);
        }
        ui.unclip();

        if (needBar) {
            int trackX = x + w - barW;
            ui.fill(trackX, listTop, barW, listH, 0x33FFFFFF);
            int thumbH = Math.max(16, (int) ((long) listH * listH / contentH));
            int thumbY = listTop + (int) ((listH - thumbH) * (scroll / maxScroll));
            ui.fill(trackX, thumbY, barW, thumbH, Theme.accent());
            ui.area(trackX - 2, listTop, barW + 4, listH, () -> {
                scroll = (ui.mouseY() - listTop) / (double) listH * maxScroll;
                ui.startDrag((px, py) -> scroll = (py - listTop) / (double) listH * maxScroll);
            });
        }
        ui.label(note, x, bottom - 16, w, Theme.MUTED);
    }

    // ------------------------------------------------------------------ Setup (saved servers)

    private void select(Servers.Entry e) {
        sel = e;
        sName.text = e.name;
        sIp.text = e.ip;
    }

    private void setup(Ui ui, NullClient c, Minecraft mc, int x, int y, int w, int h) {
        int left = Math.min(130, w * 2 / 5);
        int rx = x + left + 8;
        int rw = w - left - 8;
        List<Servers.Entry> list = Servers.all();
        ui.text("Saved servers", x, y, Theme.accent());
        int ly = y + 14;
        int rows = Math.max(2, (h - 70) / 22);
        for (int i = 0; i < Math.min(list.size(), rows); i++) {
            Servers.Entry e = list.get(i);
            ui.button((e == sel ? "> " : "") + e.name, x, ly, left, 18, () -> select(e));
            ly += 22;
        }
        if (list.isEmpty()) {
            ui.text("none yet", x, ly, Theme.MUTED);
            ly += 16;
        }
        int half = (left - 4) / 2;
        ui.button("New", x, ly + 4, half, 18, () -> {
            Servers.Entry e = Servers.add("New profile " + (Servers.all().size() + 1), sIp.text.trim());
            select(e);
        });
        ui.button("Duplicate", x + half + 4, ly + 4, half, 18, () -> {
            if (sel == null) {
                note = "select a saved server first";
            } else {
                select(Servers.add(sel.name + " (copy)", sel.ip));
            }
        });

        ui.text("Server setup", rx, y, Theme.accent());
        ui.field(sName, rx, y + 14, rw, 20);
        ui.field(sIp, rx, y + 38, rw - 76, 20);
        ui.button("Use Current", rx + rw - 72, y + 38, 72, 20, () -> {
            ServerData sd = mc.getCurrentServer();
            if (sd == null) {
                note = "you are not on a server right now";
            } else {
                sIp.text = sd.ip;
                note = "ip set to " + sd.ip;
            }
        });
        Profile a = c.profiles().active();
        ui.label("Account: " + (a == null ? "(select one in Accounts)" : a.name), rx, y + 66, rw, Theme.TEXT);
        int hb = (rw - 4) / 2;
        ui.button("Save", rx, y + 82, hb, 20, () -> {
            if (sName.text.isBlank() || sIp.text.isBlank()) {
                note = "enter a name and an address";
                return;
            }
            if (sel == null) {
                select(Servers.add(sName.text.trim(), sIp.text.trim()));
            } else {
                sel.name = sName.text.trim();
                sel.ip = sIp.text.trim();
                Servers.save();
            }
            note = "saved";
        });
        ui.button("Delete", rx + hb + 4, y + 82, hb, 20, () -> {
            if (sel != null) {
                Servers.remove(sel);
                sel = null;
                sName.text = "";
                sIp.text = "";
                note = "deleted";
            }
        });
        ui.button("Join", rx, y + 108, rw, 22, () -> {
            try {
                Profile acct = c.profiles().active();
                if (acct == null) {
                    throw new IllegalArgumentException("select an account first");
                }
                Join.joinAs(acct.name, sIp.text.trim());
                note = "joining as " + acct.name + "...";
            } catch (RuntimeException e) {
                note = String.valueOf(e.getMessage());
            }
        });
        ui.label(note, rx, y + 136, rw, Theme.MUTED);
    }

    // ------------------------------------------------------------------ Login (passwords)

    private void login(Ui ui, NullClient c, int x, int y, int w) {
        Profile cur = c.profiles().active();
        if (cur == null) {
            ui.text("Select an account first (Accounts tab).", x, y, Theme.MUTED);
            return;
        }
        if (!cur.id.equals(syncedPass)) {
            acctPass.text = cur.password;
            syncedPass = cur.id;
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
}