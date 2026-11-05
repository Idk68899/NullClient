package dev.nullclient.core.event;

import dev.nullclient.ai.AIAgent;
import dev.nullclient.core.macro.Macro;
import dev.nullclient.core.session.Profile;

public final class Events {
    private Events() {}

    public static final class ClientTick extends Event {
        public static final ClientTick INSTANCE = new ClientTick();
        private ClientTick() {}
    }

    public static final class ChatSend extends CancellableEvent {
        private final String message;
        public ChatSend(String message) { this.message = message; }
        public String message() { return message; }
    }

    public static final class AITaskState extends Event {
        public enum Kind { STARTED, RESUMED, SUSPENDED, SUCCEEDED, FAILED, CANCELLED }
        private final AIAgent agent;
        private final Kind kind;
        private final String message;
        public AITaskState(AIAgent agent, Kind kind, String message) {
            this.agent = agent; this.kind = kind; this.message = message;
        }
        public AIAgent agent() { return agent; }
        public Kind kind() { return kind; }
        public String message() { return message; }
    }

    public static final class AITick extends CancellableEvent {
        private final AIAgent agent;
        private final long ticks;
        public AITick(AIAgent agent, long ticks) { this.agent = agent; this.ticks = ticks; }
        public AIAgent agent() { return agent; }
        public long ticks() { return ticks; }
    }

    public static final class ProfileSwitch extends Event {
        private final Profile previous;
        private final Profile next;
        public ProfileSwitch(Profile previous, Profile next) { this.previous = previous; this.next = next; }
        public Profile previous() { return previous; }
        public Profile next() { return next; }
    }

    public static final class MacroState extends Event {
        public enum Kind { RECORD_START, RECORD_STOP, PLAY_START, PLAY_STOP }
        private final String name;
        private final Kind kind;
        public MacroState(String name, Kind kind) { this.name = name; this.kind = kind; }
        public String name() { return name; }
        public Kind kind() { return kind; }
    }

    public static final class UiUpdate extends Event {
        public boolean menuOpen;
        public float deltaTime;
        public long frame;
    }
}
