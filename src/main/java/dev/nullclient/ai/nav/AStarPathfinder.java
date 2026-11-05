package dev.nullclient.ai.nav;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Incremental A* over block-grid player positions (feet cell).
 *
 * <p>Move set: walk, diagonal, step-up (1 block), descend (up to {@value #MAX_FALL} blocks) and parkour
 * jumps across gaps (2-4 blocks flat, up to 3 blocks with +1 height, down to -2). Hazards are impassable and
 * cells adjacent to hazards and water carry extra cost. When the node budget is exhausted the search yields a
 * partial path to the explored node closest to the goal.
 */
public final class AStarPathfinder {
    public static final int MAX_FALL = 3;
    public static final int MAX_JUMP = 4;

    public enum State { RUNNING, FOUND, PARTIAL, FAILED }

    private AStarPathfinder() {
    }

    public static final class Search {
        private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        private static final int[] JUMP_DY = {1, 0, -1, -2};

        private final WorldView v;
        private final int gx;
        private final int gy;
        private final int gz;
        private final int maxExpansions;
        private final PathNode start;
        private final PriorityQueue<PathNode> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        private final Long2ObjectOpenHashMap<PathNode> best = new Long2ObjectOpenHashMap<>();
        private final java.util.Set<PathNode> closed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        private int expansions;
        private PathNode closest;
        private PathNode found;
        private State state = State.RUNNING;

        public Search(WorldView view, BlockPos from, BlockPos goal, int maxExpansions) {
            this.v = view;
            this.gx = goal.getX();
            this.gy = goal.getY();
            this.gz = goal.getZ();
            this.maxExpansions = maxExpansions;
            this.start = new PathNode(from.getX(), from.getY(), from.getZ(), MoveType.START, 0, heuristic(from.getX(), from.getY(), from.getZ()), null);
            this.closest = start;
            best.put(BlockPos.asLong(start.x, start.y, start.z), start);
            open.add(start);
        }

        public State state() {
            return state;
        }

        public int expansions() {
            return expansions;
        }

        /** Advances the search by at most {@code budget} node expansions. */
        public State step(int budget) {
            for (int i = 0; i < budget && state == State.RUNNING; i++) {
                PathNode cur = open.poll();
                if (cur == null) {
                    finish();
                    break;
                }
                if (best.get(BlockPos.asLong(cur.x, cur.y, cur.z)) != cur || !closed.add(cur)) {
                    continue;
                }
                expansions++;
                if (cur.x == gx && cur.z == gz && Math.abs(cur.y - gy) <= 1) {
                    found = cur;
                    state = State.FOUND;
                    break;
                }
                if (cur.h < closest.h - 1e-9) {
                    closest = cur;
                }
                if (expansions >= maxExpansions) {
                    finish();
                    break;
                }
                expand(cur);
            }
            return state;
        }

        public Path result() {
            if (state == State.FOUND) {
                return build(found, false);
            }
            if (state == State.PARTIAL) {
                return build(closest, true);
            }
            throw new IllegalStateException("No result in state " + state);
        }

        private void finish() {
            state = closest != start && closest.h < start.h - 2.0 ? State.PARTIAL : State.FAILED;
        }

        private Path build(PathNode end, boolean partial) {
            List<PathNode> nodes = new ArrayList<>();
            for (PathNode n = end; n != null; n = n.parent) {
                nodes.add(n);
            }
            Collections.reverse(nodes);
            return new Path(nodes, partial);
        }

        private double heuristic(int x, int y, int z) {
            double dx = Math.abs(gx - x);
            double dz = Math.abs(gz - z);
            double dy = gy - y;
            double horizontal = Math.max(dx, dz) + 0.414 * Math.min(dx, dz);
            return horizontal + (dy > 0 ? dy * 1.3 : -dy * 0.3);
        }

        private void offer(PathNode from, int x, int y, int z, MoveType move, double cost) {
            double extra = 0;
            if (v.isWater(x, y, z)) {
                extra += 1.5;
            }
            if (v.nearHazard(x, y, z)) {
                extra += 6.0;
            }
            double g = from.g + cost + extra;
            long key = BlockPos.asLong(x, y, z);
            PathNode existing = best.get(key);
            if (existing != null && existing.g <= g) {
                return;
            }
            PathNode n = new PathNode(x, y, z, move, g, heuristic(x, y, z), from);
            best.put(key, n);
            open.add(n);
        }

        private void expand(PathNode n) {
            int x = n.x;
            int y = n.y;
            int z = n.z;
            for (int[] d : CARDINAL) {
                int dx = d[0];
                int dz = d[1];
                int nx = x + dx;
                int nz = z + dz;
                if (v.canStand(nx, y, nz)) {
                    offer(n, nx, y, nz, MoveType.WALK, 1.0);
                } else if (!v.bodyFree(nx, y, nz)) {
                    if (v.canStand(nx, y + 1, nz) && v.bodyFree(x, y + 2, z)) {
                        offer(n, nx, y + 1, nz, MoveType.STEP_UP, 2.0);
                    }
                } else if (v.bodyFree(nx, y + 1, nz)) {
                    for (int k = 1; k <= MAX_FALL; k++) {
                        if (v.canStand(nx, y - k, nz)) {
                            offer(n, nx, y - k, nz, MoveType.DESCEND, 1.3 + 0.5 * k);
                            break;
                        }
                        if (!v.bodyFree(nx, y - k, nz)) {
                            break;
                        }
                    }
                    parkour(n, dx, dz);
                }
            }
            for (int[] d : DIAGONAL) {
                int dx = d[0];
                int dz = d[1];
                if (v.canStand(x + dx, y, z + dz)
                        && v.bodyFree(x + dx, y, z) && v.bodyFree(x + dx, y + 1, z)
                        && v.bodyFree(x, y, z + dz) && v.bodyFree(x, y + 1, z + dz)) {
                    offer(n, x + dx, y, z + dz, MoveType.DIAGONAL, 1.45);
                }
            }
        }

        private void parkour(PathNode n, int dx, int dz) {
            int x = n.x;
            int y = n.y;
            int z = n.z;
            if (!v.bodyFree(x, y + 2, z)) {
                return;
            }
            boolean runUp = v.canStand(x - dx, y, z - dz);
            for (int d = 2; d <= MAX_JUMP; d++) {
                int ix = x + dx * (d - 1);
                int iz = z + dz * (d - 1);
                if (!(v.bodyFree(ix, y, iz) && v.bodyFree(ix, y + 1, iz) && v.bodyFree(ix, y + 2, iz))) {
                    break;
                }
                if (d >= 3 && !runUp) {
                    break;
                }
                int lx = x + dx * d;
                int lz = z + dz * d;
                for (int dy : JUMP_DY) {
                    if (dy == 1 && d > 3) {
                        continue;
                    }
                    if (v.canStand(lx, y + dy, lz)) {
                        offer(n, lx, y + dy, lz, MoveType.PARKOUR, 3.0 + d + (d == MAX_JUMP ? 2.0 : 0.0));
                    }
                }
            }
        }
    }
}