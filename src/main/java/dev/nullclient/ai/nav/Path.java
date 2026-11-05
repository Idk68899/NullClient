package dev.nullclient.ai.nav;

import java.util.List;

/** Ordered node chain; index 0 is the start position. */
public final class Path {
    private final List<PathNode> nodes;
    private final boolean partial;

    public Path(List<PathNode> nodes, boolean partial) {
        this.nodes = List.copyOf(nodes);
        this.partial = partial;
    }

    public int size() {
        return nodes.size();
    }

    public PathNode get(int i) {
        return nodes.get(i);
    }

    public PathNode last() {
        return nodes.get(nodes.size() - 1);
    }

    /** True if the search ended before reaching the goal; the path leads to the closest explored node. */
    public boolean partial() {
        return partial;
    }
}