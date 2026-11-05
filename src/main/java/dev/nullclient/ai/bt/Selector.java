package dev.nullclient.ai.bt;

/** Runs children in order; succeeds on the first success, fails when all fail. */
public final class Selector extends Composite {
    public Selector(String name, boolean memory, Node... children) {
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
            if (s == Status.SUCCESS) {
                settle(i, bb);
                return Status.SUCCESS;
            }
        }
        settle(children.size() - 1, bb);
        return Status.FAILURE;
    }
}