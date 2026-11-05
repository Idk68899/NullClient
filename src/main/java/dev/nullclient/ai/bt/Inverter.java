package dev.nullclient.ai.bt;

public final class Inverter implements Node {
    private final String name;
    private final Node child;

    public Inverter(String name, Node child) {
        this.name = name;
        this.child = child;
    }

    @Override
    public Status tick(Blackboard bb) {
        Status s = child.tick(bb);
        return switch (s) {
            case SUCCESS -> Status.FAILURE;
            case FAILURE -> Status.SUCCESS;
            case RUNNING -> Status.RUNNING;
        };
    }

    @Override
    public void reset(Blackboard bb) {
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