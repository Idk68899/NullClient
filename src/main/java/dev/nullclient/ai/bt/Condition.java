package dev.nullclient.ai.bt;

import java.util.function.Predicate;

/** Leaf that checks a predicate: SUCCESS if true, FAILURE otherwise. Never RUNNING. */
public final class Condition implements Node {
    private final String name;
    private final Predicate<Blackboard> test;

    public Condition(String name, Predicate<Blackboard> test) {
        this.name = name;
        this.test = test;
    }

    @Override
    public Status tick(Blackboard bb) {
        return test.test(bb) ? Status.SUCCESS : Status.FAILURE;
    }

    @Override
    public String name() {
        return name;
    }
}