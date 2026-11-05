package dev.nullclient.ai.world;

import dev.nullclient.core.event.Events;
import dev.nullclient.core.event.Subscribe;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Incremental block index. Each tick it rescans 16x16x16 sections around the player within a small time
 * budget (skipping sections whose palette holds nothing of interest), nearest first, and remembers where the
 * interesting blocks are. After a full pass it idles briefly before rescanning.
 */
public final class WorldScanner {
    public static final WorldScanner INSTANCE = new WorldScanner();

    private static final int RADIUS_CHUNKS = 6;
    private static final long BUDGET_NANOS = 3_000_000L;
    private static final int IDLE_TICKS = 60;

    private final Map<Long, Map<String, LongArrayList>> sections = new HashMap<>();
    private final Map<Block, String> interest = new HashMap<>();
    private final Set<String> interestIds = new HashSet<>();
    private final List<int[]> queue = new ArrayList<>();
    private int cursor;
    private int idle;
    private long lastCenter = Long.MIN_VALUE;
    private ClientLevel level;

    private WorldScanner() {
    }

    public void setInterest(Set<String> ids) {
        interest.clear();
        interestIds.clear();
        interestIds.addAll(ids);
        for (Block b : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (ids.contains(id)) {
                interest.put(b, id);
            }
        }
        sections.clear();
    }

    /** True if every part of the spec names a block id this scanner indexes. */
    public boolean covers(String spec) {
        for (String part : spec.split(",")) {
            String id = part.startsWith("=") ? part.substring(1) : part;
            if (!interestIds.contains(id)) {
                return false;
            }
        }
        return true;
    }

    @Subscribe
    public void onTick(Events.ClientTick e) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lv = mc.level;
        LocalPlayer p = mc.player;
        if (lv == null || p == null || interest.isEmpty()) {
            level = null;
            sections.clear();
            return;
        }
        if (lv != level) {
            level = lv;
            sections.clear();
            lastCenter = Long.MIN_VALUE;
        }
        int pcx = p.blockPosition().getX() >> 4;
        int pcz = p.blockPosition().getZ() >> 4;
        long center = ((long) pcx << 32) ^ (pcz & 0xFFFFFFFFL);
        if (center != lastCenter) {
            rebuildQueue(pcx, Math.floorDiv(p.blockPosition().getY(), 16), pcz);
            lastCenter = center;
        }
        if (idle > 0) {
            idle--;
            return;
        }
        long end = System.nanoTime() + BUDGET_NANOS;
        while (!queue.isEmpty() && System.nanoTime() < end) {
            int[] q = queue.get(cursor);
            scan(q[0], q[1], q[2]);
            cursor++;
            if (cursor >= queue.size()) {
                cursor = 0;
                idle = IDLE_TICKS;
                break;
            }
        }
    }

    private void rebuildQueue(int pcx, int psy, int pcz) {
        queue.clear();
        cursor = 0;
        idle = 0;
        Set<Long> keep = new HashSet<>();
        for (int cx = pcx - RADIUS_CHUNKS; cx <= pcx + RADIUS_CHUNKS; cx++) {
            for (int cz = pcz - RADIUS_CHUNKS; cz <= pcz + RADIUS_CHUNKS; cz++) {
                for (int sy = level.getMinSectionY(); sy <= level.getMaxSectionY(); sy++) {
                    queue.add(new int[]{cx, sy, cz});
                    keep.add(SectionPos.asLong(cx, sy, cz));
                }
            }
        }
        queue.sort(Comparator.comparingInt(q -> (q[0] - pcx) * (q[0] - pcx) + (q[1] - psy) * (q[1] - psy) + (q[2] - pcz) * (q[2] - pcz)));
        sections.keySet().retainAll(keep);
    }

    private void scan(int cx, int sy, int cz) {
        long key = SectionPos.asLong(cx, sy, cz);
        if (!level.hasChunk(cx, cz)) {
            sections.remove(key);
            return;
        }
        LevelChunk chunk = level.getChunk(cx, cz);
        int idx = chunk.getSectionIndexFromSectionY(sy);
        if (idx < 0 || idx >= chunk.getSectionsCount()) {
            return;
        }
        LevelChunkSection sec = chunk.getSection(idx);
        if (sec.hasOnlyAir() || !sec.maybeHas(s -> interest.containsKey(s.getBlock()))) {
            sections.remove(key);
            return;
        }
        Map<String, LongArrayList> found = new HashMap<>();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    String id = interest.get(sec.getBlockState(x, y, z).getBlock());
                    if (id != null) {
                        found.computeIfAbsent(id, k -> new LongArrayList()).add(BlockPos.asLong(cx * 16 + x, sy * 16 + y, cz * 16 + z));
                    }
                }
            }
        }
        if (found.isEmpty()) {
            sections.remove(key);
        } else {
            sections.put(key, found);
        }
    }

    /** Nearest indexed blocks matching the spec, closest first. May be slightly stale; callers re-verify. */
    public List<BlockPos> find(String spec, BlockPos origin, double maxDist, int limit) {
        double max2 = maxDist * maxDist;
        List<BlockPos> out = new ArrayList<>();
        for (Map<String, LongArrayList> m : sections.values()) {
            for (Map.Entry<String, LongArrayList> e : m.entrySet()) {
                if (!BlockSpec.test(e.getKey(), spec)) {
                    continue;
                }
                LongArrayList l = e.getValue();
                for (int i = 0; i < l.size(); i++) {
                    BlockPos pos = BlockPos.of(l.getLong(i));
                    if (pos.distSqr(origin) <= max2) {
                        out.add(pos);
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(b -> b.distSqr(origin)));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public boolean has(String spec, BlockPos origin, double maxDist) {
        return !find(spec, origin, maxDist, 1).isEmpty();
    }
}