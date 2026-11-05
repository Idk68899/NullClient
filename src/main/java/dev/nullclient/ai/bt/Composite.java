package dev.nullclient.ai.bt;

import java.util.List;

/**
 * Base for Sequence and Selector. In reactive mode (memory=false) the node re-evaluates from its first
 * child on every tick, so higher-priority branches can preempt a running child. With memory=true the node
 * resumes at the child that was running.
 */
public abstract class Composite implements Node {
    protected final String name;
    protected final List<Node> children;
    protected final boolean memory;
    protected int cursor;
    protected int lastRunning = -1;

    protected Composite(String name, boolean memory, Node... children) {
        this.name = name;
        this.memory = memory;
        this.children = List.of(children);
    }

    @Override
    public String name() {
        return name;
    }

    protected final void markRunning(int idx, Blackboard bb) {
        if (lastRunning != -1 && lastRunning != idx) {
            children.get(lastRunning).reset(bb);
        }
        lastRunning = idx;
        if (memory) {
            cursor = idx;
        }
    }

    protected final void settle(int completedIdx, Blackboard bb) {
        if (lastRunning != -1 && lastRunning != completedIdx) {
            children.get(lastRunning).reset(bb);
        }
        lastRunning = -1;
        cursor = 0;
    }

    @Override
    public void reset(Blackboard bb) {
        for (Node child : children) {
            child.reset(bb);
        }
        lastRunning = -1;
        cursor = 0;
    }

    @Override
    public void describeActive(StringBuilder sb) {
        sb.append(name);
        if (lastRunning >= 0) {
            sb.append(" > ");
            children.get(lastRunning).describeActive(sb);
        }
    }
}