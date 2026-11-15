package dev.nullclient.core.session;

import java.util.UUID;

public final class Profile {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String routeId;
    public String note = "";
    public long createdAt = System.currentTimeMillis();
    public long lastUsedAt;

    /** "cracked" (typed username) or "generated" (random name). Shown in the Accounts list. */
    public String kind = "cracked";

    /** Server password used by auto login/register. Encrypted at rest when a master password is set. */
    public String password = "";
    public boolean autoLogin;

    public Profile() {
    }

    public Profile(String name) {
        this.name = name;
    }
}