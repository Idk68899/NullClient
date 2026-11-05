package dev.nullclient.ai.plan;

public record Step(Kind kind, String item, int count, Knowledge.Gather gather, Knowledge.Recipe recipe, Knowledge.Smelt smelt) {
    public enum Kind { GATHER, CRAFT, SMELT }

    public String describe() {
        return switch (kind) {
            case GATHER -> "gather " + item + " (hold " + count + ")";
            case CRAFT -> "craft " + item + " x" + (count * recipe.count);
            case SMELT -> "smelt " + count + " " + item;
        };
    }
}