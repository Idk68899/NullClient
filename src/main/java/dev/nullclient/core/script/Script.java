package dev.nullclient.core.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A script: an ordered list of actions (movement, clicks, AI and client actions) that runs step by step. */
public final class Script {
    public String name = "";
    public int repeat = 1;
    public List<Step> steps = new ArrayList<>();

    public static final class Step {
        public String kind = "wait";
        public double value = 1.0;
        public String text = "";

        public Step() {
        }

        public Step(String kind, double value, String text) {
            this.kind = kind;
            this.value = value;
            this.text = text == null ? "" : text;
        }

        public Step copy() {
            return new Step(kind, value, text);
        }
    }

    /** One kind of action. {@code unit} is "s" (seconds), "deg", "slot", "x" (times) or "" (no value). */
    public record Kind(String id, String label, String group, String unit, double def, double min, double max,
                       String textHint, String hint) {
        public boolean hasValue() {
            return !unit.isEmpty();
        }

        public boolean needsText() {
            return !textHint.isEmpty();
        }

        public String validate(double v, String text) {
            if (hasValue() && (v < min || v > max)) {
                return "the value must be between " + num(min) + " and " + num(max);
            }
            if (needsText() && (text == null || text.isBlank())) {
                return "this action needs text";
            }
            return null;
        }
    }

    private static Kind k(String id, String label, String group, String unit, double def, double min, double max, String textHint, String hint) {
        return new Kind(id, label, group, unit, def, min, max, textHint, hint);
    }

    public static final List<Kind> KINDS = List.of(
            k("walk", "Walk forward", "game", "s", 2, 0.05, 3600, "", "Holds the forward key."),
            k("sprint", "Sprint forward", "game", "s", 2, 0.05, 3600, "", "Holds forward and sprint."),
            k("back", "Walk backward", "game", "s", 1, 0.05, 3600, "", "Holds the back key."),
            k("left", "Strafe left", "game", "s", 1, 0.05, 3600, "", "Holds the left key."),
            k("right", "Strafe right", "game", "s", 1, 0.05, 3600, "", "Holds the right key."),
            k("jump", "Jump (hold)", "game", "s", 0.5, 0.05, 3600, "", "Holds the jump key; it keeps jumping while held."),
            k("sneak", "Sneak (hold)", "game", "s", 2, 0.05, 3600, "", "Holds the sneak key."),
            k("turn", "Turn", "game", "deg", 90, -720, 720, "", "Positive turns right, negative turns left."),
            k("pitch", "Look up/down", "game", "deg", 0, -90, 90, "", "-90 is straight up, 0 level, 90 straight down."),
            k("slot", "Select hotbar slot", "game", "slot", 1, 1, 9, "", "Hotbar slot 1 to 9."),
            k("attack", "Attack ahead", "game", "x", 1, 1, 200, "", "Swings at the mob in front of you, once every 0.6 s."),
            k("mine", "Mine block ahead", "game", "s", 3, 0.2, 3600, "", "Holds the break button on the block you look at."),
            k("use", "Use / place", "game", "x", 1, 1, 200, "", "Right-click on the block you look at, or use the held item."),
            k("usehold", "Hold use (eat, bow)", "game", "s", 2, 0.2, 60, "", "Holds the use button, for eating or drawing a bow."),
            k("wait", "Wait", "game", "s", 1, 0.05, 3600, "", "Does nothing for a while."),

            k("ai", "AI command", "client", "", 0, 0, 0, "get oak_log 8", "Same as typing .ai <text>. Starts it and moves on."),
            k("aitask", "AI task (wait until done)", "client", "s", 600, 5, 7200, "get oak_log 8", "Runs .ai <text> and waits until the AI is idle. The value is the time limit."),
            k("aitoggle", "Toggle AI feature", "client", "", 0, 0, 0, "vision", "vision, combat, logout or talk."),
            k("aistart", "Start AI engine", "client", "", 0, 0, 0, "", "Same as .ai start."),
            k("aioff", "Turn AI off", "client", "", 0, 0, 0, "", "Same as .ai off."),
            k("stopall", "Stop AI tasks", "client", "", 0, 0, 0, "", "Same as .ai stopall."),
            k("macro", "Play recorded macro", "client", "", 0, 0, 0, "macro name", "Plays a recorded macro and waits until it ends."),
            k("chat", "Send chat / command", "client", "", 0, 0, 0, "/home base", "Sent to the server. Starts with / for a command. At most one per 0.8 s."),
            k("say", "Show message to me", "client", "", 0, 0, 0, "message", "Shows a line in your own chat only."),
            k("leave", "Leave server", "client", "", 0, 0, 0, "", "Disconnects and ends the script."));

    public static Kind kind(String id) {
        for (Kind k : KINDS) {
            if (k.id().equals(id)) {
                return k;
            }
        }
        return null;
    }

    static String num(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** A readable one-line description of a step. */
    public static String describe(Step s) {
        Kind k = kind(s.kind);
        if (k == null) {
            return "? " + s.kind;
        }
        StringBuilder sb = new StringBuilder(k.label());
        if (k.hasValue()) {
            sb.append(' ');
            switch (k.unit()) {
                case "s" -> sb.append(String.format(Locale.ROOT, "%.1fs", s.value));
                case "deg" -> sb.append(String.format(Locale.ROOT, "%.0f deg", s.value));
                case "slot" -> sb.append(String.format(Locale.ROOT, "#%.0f", s.value));
                default -> sb.append(String.format(Locale.ROOT, "x%.0f", s.value));
            }
        }
        if (k.needsText() && s.text != null && !s.text.isBlank()) {
            sb.append(" \"").append(s.text).append('"');
        }
        return sb.toString();
    }

    public Script copy() {
        Script c = new Script();
        c.name = name;
        c.repeat = repeat;
        for (Step s : steps) {
            c.steps.add(s.copy());
        }
        return c;
    }
}