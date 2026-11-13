package dev.nullclient.ui;

import dev.nullclient.NullClient;
import dev.nullclient.core.session.Profile;
import dev.nullclient.core.session.Route;
import net.minecraft.client.gui.components.EditBox;

import java.util.ArrayList;
import java.util.List;

/** SOCKS5 / HTTP routes: list, test, assign to the selected account, add, remove. */
final class ProxyTab implements Tab {
    private static String nameText = "";
    private static String hostText = "";
    private static String portText = "1080";
    private static String userText = "";
    private static String passText = "";
    private static Route.Type type = Route.Type.SOCKS5;
    private static String note = "";
    private static boolean wasTesting;

    private EditBox name;
    private EditBox host;
    private EditBox port;
    private EditBox user;
    private EditBox pass;

    @Override
    public String title() {
        return "Proxy";
    }

    @Override
    public void build(NullScreen ui, int x, int y, int w) {
        NullClient c = NullScreen.nc();
        if (c.store().isLocked()) {
            ui.label("Unlock the store in the Accounts tab first.", x, y, w);
            return;
        }
        Profile active = c.profiles().active();
        ui.label("Routes for: " + (active == null ? "(select an account first)" : active.name) + "  - tap to use", x, y, w);
        y += 22;
        List<Route> routes = new ArrayList<>(c.routes().all());
        int shown = Math.min(routes.size(), 5);
        if (routes.isEmpty()) {
            ui.label("No routes yet - add one below.", x, y, w);
            y += 22;
        }
        for (int i = 0; i < shown; i++) {
            Route r = routes.get(i);
            boolean used = active != null && r.id.equals(active.routeId);
            ui.button((used ? "[x] " : "[ ] ") + r.name + "  " + r.endpoint() + "  " + r.status, x, y, w - 52, () -> {
                if (active == null) {
                    note = "select an account first";
                } else {
                    c.routes().assign(active, used ? null : r);
                    note = used ? "route removed from " + active.name : active.name + " now uses " + r.name;
                }
                ui.reopen();
            });
            ui.button("T", x + w - 50, y, 24, () -> {
                c.routes().test(r);
                wasTesting = true;
                note = "testing " + r.name + "...";
                ui.reopen();
            });
            ui.button("x", x + w - 24, y, 24, () -> {
                c.routes().remove(r);
                note = "removed " + r.name;
                ui.reopen();
            });
            y += 22;
        }
        if (routes.size() > shown) {
            ui.label("... and " + (routes.size() - shown) + " more", x, y, w);
            y += 22;
        }
        y += 6;
        int a = w * 2 / 5;
        name = ui.box(nameText, x, y, a - 2, "label", 32);
        host = ui.box(hostText, x + a, y, w - a, "host or ip", 128);
        y += 24;
        int q = w / 4;
        port = ui.box(portText, x, y, q - 2, "port", 5);
        ui.button(type.name(), x + q, y, q - 2, () -> {
            type = type == Route.Type.SOCKS5 ? Route.Type.HTTP : Route.Type.SOCKS5;
            ui.reopen();
        });
        user = ui.box(userText, x + 2 * q, y, q - 2, "user", 64);
        pass = ui.box(passText, x + 3 * q, y, w - 3 * q, "pass", 64);
        y += 24;
        ui.button("Add route", x, y, w, () -> {
            try {
                int p = Integer.parseInt(port.getValue().trim());
                Route r = c.routes().add(name.getValue(), type, host.getValue(), p, user.getValue(), pass.getValue());
                name.setValue("");
                host.setValue("");
                user.setValue("");
                pass.setValue("");
                note = "added " + r.name;
            } catch (NumberFormatException e) {
                note = "port must be a number";
            } catch (RuntimeException e) {
                note = String.valueOf(e.getMessage());
            }
            ui.reopen();
        });
        y += 24;
        ui.label(note, x, y, w);
    }

    @Override
    public void save() {
        if (name != null) {
            nameText = name.getValue();
            hostText = host.getValue();
            portText = port.getValue();
            userText = user.getValue();
            passText = pass.getValue();
        }
    }

    @Override
    public boolean typing() {
        return (name != null && name.isFocused()) || (host != null && host.isFocused())
                || (port != null && port.isFocused()) || (user != null && user.isFocused())
                || (pass != null && pass.isFocused());
    }

    /** When a background test finishes, rebuild so the new status shows. */
    @Override
    public void refresh(NullScreen ui) {
        boolean now = false;
        for (Route r : NullScreen.nc().routes().all()) {
            now |= r.testing;
        }
        if (wasTesting && !now) {
            wasTesting = false;
            ui.reopen();
            return;
        }
        wasTesting = wasTesting || now;
    }
}