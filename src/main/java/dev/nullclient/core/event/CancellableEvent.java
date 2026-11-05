package dev.nullclient.core.event;

public abstract class CancellableEvent extends Event {
    private boolean cancelled;

    public final boolean isCancelled() {
        return cancelled;
    }

    public final void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}