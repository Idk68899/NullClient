package dev.nullclient.gui;

import dev.nullclient.NullClient;
import dev.nullclient.core.session.Profile;
import dev.nullclient.core.session.Route;

import java.util.ArrayList;
import java.util.List;

/** SOCKS5 / HTTP routes: list, test, assign to the selected account, add, remove. Stored and tested only. */
final class ProxyPage implements Page {
    private final Field name = new Field(32).hint("label");
    private final Field host = new Field(128).hint("host or ip");
    private final Field port = new Field(5).hint("port");
    private final Field user = new Field(64).hint("user");
    private final Field pass = new Field(64).hint("pass");
    private Route.Type type = Route.Type.SOCKS5;
    private int pageIdx;
    private String note = "";

    ProxyPage() {
        port.text = "1080";
    }

    @Override
    public String title() {
        return "Proxy";
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        NullClient c = Ui.nc();
        if (c.store().isLocked()) {
            ui.text("Unlock the store in the Accounts tab first.", x, y, Theme.WARN);
            return;
        }
        Profile active = c.profiles().active();
        ui.label("Routes for: " + (active == null ? "(select an account first)" : active.name) + "  - tap to use", x, y, w, Theme.accent());
        y += 14;
        List<Route> routes = new ArrayList<>(c.routes().all());
        int per = 3;
        int pages = Math.max(1, (routes.size() + per - 1) / per);
        pageIdx = Math.max(0, Math.min(pageIdx, pages - 1));
        if (routes.isEmpty()) {
            ui.text("No routes yet - add one below.", x, y, Theme.MUTED);
            y += 18;
        }
        for (int i = pageIdx * per; i < Math.min(routes.size(), (pageIdx + 1) * per); i++) {
            Route r = routes.get(i);
            boolean used = active != null && r.id.equals(active.routeId);
            ui.button((used ? "[x] " : "[ ] ") + r.name + "  " + r.endpoint() + "  " + r.status, x, y, w - 52, 18, () -> {
                if (active == null) {
                    note = "select an account first";
                } else {
                    c.routes().assign(active, used ? null : r);
                    note = used ? "route removed from " + active.name : active.name + " now uses " + r.name;
                }
            });
            ui.button("T", x + w - 50, y, 24, 18, () -> {
                c.routes().test(r);
                note = "testing " + r.name + "...";
            });
            ui.button("x", x + w - 24, y, 24, 18, () -> {
                c.routes().remove(r);
                note = "removed " + r.name;
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
        int a = w * 2 / 5;
        ui.field(name, x, y, a - 4, 20);
        ui.field(host, x + a, y, w - a, 20);
        y += 24;
        int q = w / 4;
        ui.field(port, x, y, q - 4, 20);
        ui.button(type.name(), x + q, y, q - 4, 20, () -> type = type == Route.Type.SOCKS5 ? Route.Type.HTTP : Route.Type.SOCKS5);
        ui.field(user, x + 2 * q, y, q - 4, 20);
        ui.field(pass, x + 3 * q, y, w - 3 * q, 20);
        y += 24;
        ui.button("Add route", x, y, w, 20, () -> {
            try {
                Route r = c.routes().add(name.text, type, host.text, Integer.parseInt(port.text.trim()), user.text, pass.text);
                name.text = "";
                host.text = "";
                user.text = "";
                pass.text = "";
                note = "added " + r.name;
            } catch (NumberFormatException e) {
                note = "port must be a number";
            } catch (RuntimeException e) {
                note = String.valueOf(e.getMessage());
            }
        });
        y += 26;
        ui.label(note, x, y, w, Theme.MUTED);
    }
}