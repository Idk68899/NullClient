package dev.nullclient.ai.bt;

import java.util.function.Consumer;
import java.util.function.Function;

/** Leaf that performs an action and reports its status. */
public final class Task implements Node {
    private final String name;
    private final Function<Blackboard, Status> action;
    private final Consumer<Blackboard> onReset;

    public Task(String name, Function<Blackboard, Status> action) {
        this(name, action, bb -> {
        });
    }

    public Task(String name, Function<Blackboard, Status> action, Consumer<Blackboard> onReset) {
        this.name = name;
        this.action = action;
        this.onReset = onReset;
    }

    @Override
    public Status tick(Blackboard bb) {
        return action.apply(bb);
    }

    @Override
    public void reset(Blackboard bb) {
        onReset.accept(bb);
    }

    @Override
    public String name() {
        return name;
    }
}