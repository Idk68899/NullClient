package dev.nullclient.ai.nav;

public final class PathNode {
    public final int x;
    public final int y;
    public final int z;
    public final MoveType move;
    final double g;
    final double h;
    final PathNode parent;

    PathNode(int x, int y, int z, MoveType move, double g, double h, PathNode parent) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.move = move;
        this.g = g;
        this.h = h;
        this.parent = parent;
    }

    double f() {
        return g + h;
    }
}