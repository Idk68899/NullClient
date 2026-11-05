package dev.nullclient.ai.bt;

/** Runs children in order; fails on the first failure, succeeds when all succeed. */
public final class Sequence extends Composite {
    public Sequence(String name, boolean memory, Node... children) {
        super(name, memory, children);
    }

    @Override
    public Status tick(Blackboard bb) {
        for (int i = memory ? cursor : 0; i < children.size(); i++) {
            Status s = children.get(i).tick(bb);
            if (s == Status.RUNNING) {
                markRunning(i, bb);
                return Status.RUNNING;
            }
            if (s == Status.FAILURE) {
                settle(i, bb);
                return Status.FAILURE;
            }
        }
        settle(children.size() - 1, bb);
        return Status.SUCCESS;
    }
}