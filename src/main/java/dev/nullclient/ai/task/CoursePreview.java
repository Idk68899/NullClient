package dev.nullclient.ai.task;

import dev.nullclient.ai.nav.AStarPathfinder;
import dev.nullclient.ai.nav.MoveType;
import dev.nullclient.ai.nav.Path;
import dev.nullclient.ai.nav.PathNode;
import dev.nullclient.ai.nav.WorldView;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Plans every leg of a course without moving (a few milliseconds per frame) so the window can show the route and the blockers. */
public final class CoursePreview {
    public static final class Leg {
        public boolean done;
        public boolean ok;
        /** x, y, z, and 1 if the step into this node is a jump. */
        public final List<int[]> nodes = new ArrayList<>();
        public int jumps;
        public String problem = "";
        public List<String> blockers = List.of();
    }

    private final List<BlockPos> pts;
    private final BlockPos start;
    private final int maxFall;
    private final List<Leg> legs = new ArrayList<>();
    private int cur;
    private AStarPathfinder.Search search;

    public CoursePreview(List<BlockPos> pts, LocalPlayer p) {
        this.pts = new ArrayList<>(pts);
        this.start = p.blockPosition();
        this.maxFall = NavTasks.fallLimit(p);
        for (int i = 0; i < pts.size(); i++) {
            legs.add(new Leg());
        }
    }

    public List<Leg> legs() {
        return legs;
    }

    public boolean finished() {
        return cur >= pts.size();
    }

    public void step(ClientLevel level) {
        if (finished()) {
            return;
        }
        BlockPos from = cur == 0 ? start : pts.get(cur - 1);
        BlockPos goal = pts.get(cur);
        if (search == null) {
            search = new AStarPathfinder.Search(new WorldView(level), from, goal, 20_000, false, false).maxFall(maxFall);
        }
        long end = System.nanoTime() + 4_000_000L;
        AStarPathfinder.State st = search.state();
        while (st == AStarPathfinder.State.RUNNING && System.nanoTime() < end) {
            st = search.step(40);
        }
        if (st == AStarPathfinder.State.RUNNING) {
            return;
        }
        Leg leg = legs.get(cur);
        leg.done = true;
        if (st == AStarPathfinder.State.FAILED) {
            leg.problem = "no walking or jumping route";
            leg.blockers = Obstacles.between(level, from, goal);
        } else {
            Path path = search.result();
            for (int i = 0; i < path.size(); i++) {
                PathNode n = path.get(i);
                boolean jump = n.move == MoveType.PARKOUR;
                leg.nodes.add(new int[]{n.x, n.y, n.z, jump ? 1 : 0});
                if (jump) {
                    leg.jumps++;
                }
            }
            leg.ok = st == AStarPathfinder.State.FOUND;
            if (!leg.ok) {
                PathNode last = path.last();
                leg.problem = "the route stops at " + last.x + " " + last.y + " " + last.z + " (short of the checkpoint)";
                leg.blockers = Obstacles.between(level, new BlockPos(last.x, last.y, last.z), goal);
            }
        }
        search = null;
        cur++;
    }
}