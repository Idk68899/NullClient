package dev.nullclient.gui;

import dev.nullclient.NullClient;
import dev.nullclient.core.session.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Popup shown when a server asks you to /login or /register: type the password, optionally remember it. */
final class PromptScreen extends Screen {
    private final String kind;
    private final Profile profile;
    private final Field pass = new Field(64).hint("password").masked();
    private final Ui ui = new Ui();
    private boolean remember = true;
    private String note = "";

    PromptScreen(String kind, Profile profile) {
        super(Component.literal("NullClient login"));
        this.kind = kind;
        this.profile = profile;
        this.pass.onEnter = this::submit;
        if (profile != null && !profile.password.isEmpty()) {
            this.pass.text = profile.password;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void submit() {
        String pw = pass.text;
        if (pw.isEmpty()) {
            note = "enter a password first";
            return;
        }
        if (remember && profile != null) {
            profile.password = pw;
            profile.autoLogin = true;
            NullClient.get().store().save();
        }
        AutoLogin.send(kind, pw);
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ui.begin(g, mouseX, mouseY);
        ui.fill(0, 0, this.width, this.height, 0x88000000);
        int w = Math.min(this.width - 20, 300);
        int h = 128;
        int x = (this.width - w) / 2;
        int y = Math.max(6, (this.height - h) / 2);
        ui.panel(x, y, w, h, "panel");
        boolean register = kind.equals("register");
        ui.text(register ? "This server wants you to /register" : "This server wants you to /login", x + 8, y + 8, Theme.accent());
        ui.label("Account: " + Minecraft.getInstance().getUser().getName()
                + (profile == null ? "  (not in your Accounts list)" : ""), x + 8, y + 22, w - 16, Theme.MUTED);
        ui.field(pass, x + 8, y + 38, w - 70, 20);
        ui.button("Paste", x + w - 58, y + 38, 50, 20, () -> Ui.paste(pass));
        if (profile != null) {
            ui.button((remember ? "[x]" : "[ ]") + " Remember and auto-login next time", x + 8, y + 62, w - 16, 18, () -> remember = !remember);
        }
        ui.button(register ? "Register" : "Login", x + 8, y + 86, (w - 24) / 2, 20, this::submit);
        ui.button("Cancel", x + 16 + (w - 24) / 2, y + 86, (w - 24) / 2, 20, () -> Minecraft.getInstance().gui.setScreen(null));
        ui.label(note, x + 8, y + 110, w - 16, Theme.BAD);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        ui.click((int) event.x(), (int) event.y());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (ui.key(event.key())) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return ui.type(event.codepointAsString()) || super.charTyped(event);
    }
}