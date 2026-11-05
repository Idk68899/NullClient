package dev.nullclient.ai.bt;

public interface Node {
    Status tick(Blackboard bb);

    /** Called when the node is interrupted or restarted; drop any internal state. */
    default void reset(Blackboard bb) {
    }

    String name();

    /** Appends the chain of currently running nodes, for debugging overlays. */
    default void describeActive(StringBuilder sb) {
        sb.append(name());
    }
}