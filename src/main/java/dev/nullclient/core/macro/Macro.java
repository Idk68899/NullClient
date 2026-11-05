package dev.nullclient.core.macro;

import java.util.ArrayList;
import java.util.List;

/** A recorded input sequence: delta-compressed frames of movement actions and view angles. */
public final class Macro {
    public static final class Frame {
        public int tick;
        public int actions;
        public float yaw;
        public float pitch;

        public Frame() {
        }

        public Frame(int tick, int actions, float yaw, float pitch) {
            this.tick = tick;
            this.actions = actions;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    public String name = "";
    public int length;
    public List<Frame> frames = new ArrayList<>();
}