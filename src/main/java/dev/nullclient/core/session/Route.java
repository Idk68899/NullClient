package dev.nullclient.core.session;

import java.util.UUID;

public final class Route {
    public enum Type { SOCKS5, HTTP }

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public Type type = Type.SOCKS5;
    public String host = "";
    public int port = 1080;
    public String username = "";
    public String password = "";
    public boolean enabled = true;

    public transient long latencyMs = -1;
    public transient String status = "untested";
    public transient boolean testing;

    public Route() {
    }

    public boolean hasAuth() {
        return username != null && !username.isEmpty();
    }

    public String endpoint() {
        return type + " " + host + ":" + port;
    }
}