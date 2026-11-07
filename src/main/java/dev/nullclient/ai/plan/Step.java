package dev.nullclient.ai.plan;

public record Step(Kind kind, String item, int count, Knowledge.Gather gather, Knowledge.Recipe recipe, Knowledge.Smelt smelt, int delta) {
    public enum Kind { GATHER, CRAFT, SMELT_START, SMELT_COLLECT }

    public Step(Kind kind, String item, int count, Knowledge.Gather gather, Knowledge.Recipe recipe, Knowledge.Smelt smelt) {
        this(kind, item, count, gather, recipe, smelt, 0);
    }

    public String describe() {
        return switch (kind) {
            case GATHER -> "gather " + item + " (hold " + count + ")";
            case CRAFT -> "craft " + item + " x" + (count * recipe.count);
            case SMELT_START -> "load furnace: " + count + " " + smelt.in + " -> " + item;
            case SMELT_COLLECT -> "collect " + count + " " + item + " from furnace";
        };
    }
}