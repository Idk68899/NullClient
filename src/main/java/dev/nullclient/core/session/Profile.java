package dev.nullclient.core.session;

import java.util.UUID;

public final class Profile {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String routeId;
    public String note = "";
    public long createdAt = System.currentTimeMillis();
    public long lastUsedAt;

    public Profile() {
    }

    public Profile(String name) {
        this.name = name;
    }
}