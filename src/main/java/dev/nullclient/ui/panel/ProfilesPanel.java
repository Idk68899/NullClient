package dev.nullclient.ui.panel;

import dev.nullclient.NullClient;
import dev.nullclient.core.session.Profile;
import dev.nullclient.core.session.ProfileManager;
import dev.nullclient.core.session.Route;
import dev.nullclient.core.session.SessionStore;
import dev.nullclient.ui.GlassTheme;
import dev.nullclient.ui.Panel;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class ProfilesPanel extends Panel {
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final NullClient nc;
    private final ImString unlockPw = new ImString(128);
    private final ImString newPw = new ImString(128);
    private final ImString confirmPw = new ImString(128);
    private final ImString newName = new ImString(32);
    private final ImString renameBuf = new ImString(32);
    private final ImString noteBuf = new ImString(256);
    private final ImInt routeIdx = new ImInt(0);

    private String selectedId;
    private String syncedId;
    private String info = "";
    private boolean infoIsError;

    public ProfilesPanel(NullClient nc) {
        super("Profiles", true, 40f, 60f, 560f, 620f);
        this.nc = nc;
    }

    private void say(String text, boolean error) {
        info = text;
        infoIsError = error;
    }

    @Override
    protected void draw() {
        SessionStore store = nc.store();
        if (store.isLocked()) {
            drawLocked(store);
            return;
        }
        drawAuth(store);
        ImGui.separator();
        drawCreate();
        ImGui.spacing();
        drawTable();
        ImGui.spacing();
        drawDetails();
        if (!info.isEmpty()) {
            ImGui.spacing();
            GlassTheme.colored(infoIsError ? GlassTheme.RED : GlassTheme.GREEN, info);
        }
    }

    private void drawLocked(SessionStore store) {
        GlassTheme.colored(GlassTheme.AMBER, "Profile store is locked.");
        ImGui.textWrapped("Enter the global master password to decrypt profiles and routes.");
        ImGui.setNextItemWidth(320f);
        boolean enter = ImGui.inputText("##unlock", unlockPw, ImGuiInputTextFlags.Password | ImGuiInputTextFlags.EnterReturnsTrue);
        ImGui.sameLine();
        if (ImGui.button("Unlock") || enter) {
            char[] pw = unlockPw.get().toCharArray();
            unlockPw.set("");
            if (store.unlock(pw)) {
                say("Unlocked.", false);
            } else {
                say("Wrong password.", true);
            }
        }
        if (!info.isEmpty()) {
            GlassTheme.colored(infoIsError ? GlassTheme.RED : GlassTheme.GREEN, info);
        }
    }

    private void drawAuth(SessionStore store) {
        if (!ImGui.collapsingHeader("Global Authentication", ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }
        if (store.isProtected()) {
            GlassTheme.colored(GlassTheme.GREEN, "Encrypted at rest (AES-256-GCM, PBKDF2-SHA256). One password covers all profiles and routes.");
        } else {
            GlassTheme.colored(GlassTheme.AMBER, "Not protected: profiles and route credentials are stored as plain JSON.");
        }
        ImGui.setNextItemWidth(260f);
        ImGui.inputText("New master password", newPw, ImGuiInputTextFlags.Password);
        ImGui.setNextItemWidth(260f);
        ImGui.inputText("Confirm", confirmPw, ImGuiInputTextFlags.Password);
        if (ImGui.button(store.isProtected() ? "Change master password" : "Enable master password")) {
            String a = newPw.get();
            String b = confirmPw.get();
            if (a.length() < 8) {
                say("Password must be at least 8 characters.", true);
            } else if (!a.equals(b)) {
                say("Passwords do not match.", true);
            } else {
                try {
                    store.setMasterPassword(a.toCharArray());
                    say("Master password set; store encrypted.", false);
                } catch (GeneralSecurityException ex) {
                    say("Encryption failed: " + ex.getMessage(), true);
                }
                newPw.set("");
                confirmPw.set("");
            }
        }
        if (store.isProtected()) {
            ImGui.sameLine();
            if (ImGui.button("Remove protection")) {
                store.removeMasterPassword();
                say("Protection removed; store is now plain JSON.", false);
            }
        }
    }

    private void drawCreate() {
        ImGui.setNextItemWidth(220f);
        boolean enter = ImGui.inputTextWithHint("##newname", "profile name", newName, ImGuiInputTextFlags.EnterReturnsTrue);
        ImGui.sameLine();
        if (ImGui.button("Create") || enter) {
            try {
                Profile p = nc.profiles().create(newName.get());
                selectedId = p.id;
                newName.set("");
                say("Created '" + p.name + "'.", false);
            } catch (RuntimeException ex) {
                say(ex.getMessage(), true);
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Generate")) {
            Profile p = nc.profiles().generate();
            selectedId = p.id;
            say("Generated '" + p.name + "'.", false);
        }
    }

    private void drawTable() {
        List<Profile> list = new ArrayList<>(nc.profiles().all());
        Profile active = nc.profiles().active();
        if (ImGui.beginTable("profiles", 4, ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp)) {
            ImGui.tableSetupColumn("Name");
            ImGui.tableSetupColumn("Route");
            ImGui.tableSetupColumn("Last used");
            ImGui.tableSetupColumn("Actions");
            ImGui.tableHeadersRow();
            for (int i = 0; i < list.size(); i++) {
                Profile p = list.get(i);
                ImGui.pushID(i);
                ImGui.tableNextRow();
                ImGui.tableNextColumn();
                boolean isActive = p == active;
                if (isActive) {
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.20f, 1.00f, 0.60f, 1.00f);
                }
                if (ImGui.selectable(p.name + (isActive ? "  (active)" : ""), p.id.equals(selectedId))) {
                    selectedId = p.id;
                }
                if (isActive) {
                    ImGui.popStyleColor();
                }
                ImGui.tableNextColumn();
                Route r = nc.profiles().routeOf(p);
                ImGui.text(r == null ? "-" : r.name);
                ImGui.tableNextColumn();
                ImGui.text(p.lastUsedAt == 0 ? "never" : FMT.format(new Date(p.lastUsedAt)));
                ImGui.tableNextColumn();
                if (!isActive && ImGui.button("Activate")) {
                    nc.profiles().setActive(p);
                }
                if (!isActive) {
                    ImGui.sameLine();
                }
                if (ImGui.button("Delete")) {
                    nc.profiles().delete(p);
                    if (p.id.equals(selectedId)) {
                        selectedId = null;
                    }
                    say("Deleted '" + p.name + "'.", false);
                }
                ImGui.popID();
            }
            ImGui.endTable();
        }
    }

    private void drawDetails() {
        Profile p = nc.profiles().byId(selectedId).orElse(null);
        if (p == null) {
            ImGui.textDisabled("Select a profile to edit it.");
            return;
        }
        if (!p.id.equals(syncedId)) {
            renameBuf.set(p.name);
            noteBuf.set(p.note);
            syncedId = p.id;
        }
        ImGui.separator();
        GlassTheme.colored(GlassTheme.CYAN, "Editing: " + p.name);
        ImGui.setNextItemWidth(220f);
        ImGui.inputText("Name", renameBuf);
        ImGui.setNextItemWidth(320f);
        ImGui.inputText("Note", noteBuf);

        List<Route> routes = new ArrayList<>(nc.routes().all());
        String[] labels = new String[routes.size() + 1];
        labels[0] = "(no route)";
        int current = 0;
        for (int i = 0; i < routes.size(); i++) {
            labels[i + 1] = routes.get(i).name + "  [" + routes.get(i).endpoint() + "]";
            if (routes.get(i).id.equals(p.routeId)) {
                current = i + 1;
            }
        }
        routeIdx.set(current);
        ImGui.setNextItemWidth(320f);
        if (ImGui.combo("Route", routeIdx, labels)) {
            nc.routes().assign(p, routeIdx.get() == 0 ? null : routes.get(routeIdx.get() - 1));
        }
        if (ImGui.button("Save changes")) {
            try {
                nc.profiles().rename(p, renameBuf.get());
                nc.profiles().setNote(p, noteBuf.get());
                say("Saved.", false);
            } catch (RuntimeException ex) {
                say(ex.getMessage(), true);
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Activate##details")) {
            nc.profiles().setActive(p);
        }
        ImGui.textDisabled("AI handle: .ai " + ProfileManager.describe(p) + " goto <x> <y> <z>");
    }
}