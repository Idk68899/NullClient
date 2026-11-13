package dev.nullclient.ui;

import dev.nullclient.NullClient;
import dev.nullclient.core.session.Profile;
import net.minecraft.client.gui.components.EditBox;

import java.util.ArrayList;
import java.util.List;

/** Local accounts (offline usernames). Tap one to select it. Joining a server/world comes next. */
final class AccountsTab implements Tab {
    private static String nameText = "";
    private static String note = "";

    private EditBox nameBox;
    private EditBox passBox;

    @Override
    public String title() {
        return "Accounts";
    }

    @Override
    public void build(NullScreen ui, int x, int y, int w) {
        NullClient c = NullScreen.nc();
        if (c.store().isLocked()) {
            ui.label("Profile store is locked. Enter the master password:", x, y, w);
            y += 22;
            passBox = ui.box("", x, y, w - 64, "password", 128);
            ui.button("Unlock", x + w - 60, y, 60, () -> {
                boolean ok = c.store().unlock(passBox.getValue().toCharArray());
                note = ok ? "unlocked" : "wrong password";
                ui.reopen();
            });
            y += 26;
            ui.label(note, x, y, w);
            return;
        }
        List<Profile> list = new ArrayList<>(c.profiles().all());
        Profile active = c.profiles().active();
        int shown = Math.min(list.size(), 7);
        ui.label("Accounts - tap one to select it", x, y, w);
        y += 22;
        if (list.isEmpty()) {
            ui.label("None yet - add a username below.", x, y, w);
            y += 22;
        }
        for (int i = 0; i < shown; i++) {
            Profile p = list.get(i);
            ui.button((p == active ? "[x] " : "[ ] ") + p.name, x, y, w - 26, () -> {
                c.profiles().setActive(p);
                note = "selected " + p.name;
                ui.reopen();
            });
            ui.button("x", x + w - 22, y, 22, () -> {
                c.profiles().delete(p);
                note = "deleted " + p.name;
                ui.reopen();
            });
            y += 22;
        }
        if (list.size() > shown) {
            ui.label("... and " + (list.size() - shown) + " more", x, y, w);
            y += 22;
        }
        y += 6;
        nameBox = ui.box(nameText, x, y, w - 134, "username", 24);
        ui.button("Add", x + w - 130, y, 62, () -> {
            try {
                Profile p = c.profiles().create(nameBox.getValue());
                c.profiles().setActive(p);
                nameBox.setValue("");
                note = "added " + p.name;
            } catch (RuntimeException ex) {
                note = String.valueOf(ex.getMessage());
            }
            ui.reopen();
        });
        ui.button("Random", x + w - 64, y, 64, () -> {
            Profile p = c.profiles().generate();
            c.profiles().setActive(p);
            note = "generated " + p.name;
            ui.reopen();
        });
        y += 26;
        ui.label(note.isEmpty() ? "Join server / world buttons are coming next." : note, x, y, w);
    }

    @Override
    public void save() {
        if (nameBox != null) {
            nameText = nameBox.getValue();
        }
    }

    @Override
    public boolean typing() {
        return (nameBox != null && nameBox.isFocused()) || (passBox != null && passBox.isFocused());
    }
}