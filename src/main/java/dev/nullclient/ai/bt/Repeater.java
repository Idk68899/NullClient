package dev.nullclient.ai.bt;

/**
 * Re-runs its child after each completion, one iteration per tick at most. limit &lt; 0 repeats forever.
 * Stops with FAILURE as soon as the child fails.
 */
public final class Repeater implements Node {
    private final String name;
    private final int limit;
    private final Node child;
    private int count;

    public Repeater(String name, int limit, Node child) {
        this.name = name;
        this.limit = limit;
        this.child = child;
    }

    @Override
    public Status tick(Blackboard bb) {
        Status s = child.tick(bb);
        if (s == Status.RUNNING) {
            return Status.RUNNING;
        }
        child.reset(bb);
        if (s == Status.FAILURE) {
            count = 0;
            return Status.FAILURE;
        }
        if (limit >= 0 && ++count >= limit) {
            count = 0;
            return Status.SUCCESS;
        }
        return Status.RUNNING;
    }

    @Override
    public void reset(Blackboard bb) {
        count = 0;
        child.reset(bb);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void describeActive(StringBuilder sb) {
        sb.append(name).append(" > ");
        child.describeActive(sb);
    }
}