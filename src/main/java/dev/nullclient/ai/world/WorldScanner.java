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
 * budget (skipping sections whose palette holds nothing of interest), nearest first.
 *
 * <p>It learns new block types on demand ({@link #ensureIndexed}), except very common blocks (dirt, stone, sand...)
 * which would swamp the index; callers look for those directly around the player. The area around the player
 * is also refreshed every half second so freshly placed or broken blocks (a crafting table the AI just placed)
 * are found immediately.
 */
public final class WorldScanner {
    public static final WorldScanner INSTANCE = new WorldScanner();

    private static final int RADIUS_CHUNKS = 6;
    private static final long BUDGET_NANOS = 2_000_000L;
    private static final int IDLE_TICKS = 200;
    private static final int REFRESH_EVERY_TICKS = 10;

    private static final Set<String> COMMON = Set.of(
            "stone", "dirt", "grass_block", "coarse_dirt", "podzol", "mycelium", "sand", "red_sand", "gravel", "clay",
            "mud", "snow", "snow_block", "ice", "packed_ice", "deepslate", "cobbled_deepslate", "tuff", "andesite",
            "diorite", "granite", "calcite", "dripstone_block", "netherrack", "basalt", "blackstone", "end_stone",
            "sandstone", "red_sandstone", "terracotta", "water", "lava", "bedrock", "moss_block", "short_grass",
            "tall_grass", "fern", "large_fern", "cobblestone");

    private final Map<Long, Map<String, LongArrayList>> sections = new HashMap<>();
    private final Map<Block, String> interest = new HashMap<>();
    private final Set<String> interestIds = new HashSet<>();
    private final List<int[]> queue = new ArrayList<>();
    private int cursor;
    private int idle;
    private boolean fullPass;
    private long lastRefreshTick = Long.MIN_VALUE;
    private long lastCenter = Long.MIN_VALUE;
    private ClientLevel level;

    private WorldScanner() {
    }

    private static boolean common(String id) {
        return COMMON.contains(id) || id.endsWith("_leaves") || id.endsWith("_terracotta") || id.endsWith("_concrete_powder");
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
        fullPass = false;
    }

    /**
     * Starts indexing every uncommon block the spec matches. Returns true if all blocks the spec matches are
     * indexed (so {@link #find} can be trusted once {@link #settled()} is true); false if some are too common to
     * index, in which case the caller should scan around the player instead.
     */
    public boolean ensureIndexed(String spec) {
        Set<Block> wanted = BlockSpec.blocks(spec);
        if (wanted.isEmpty()) {
            return false;
        }
        boolean changed = false;
        boolean all = true;
        for (Block b : wanted) {
            if (interest.containsKey(b)) {
                continue;
            }
            String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (common(id)) {
                all = false;
                continue;
            }
            interest.put(b, id);
            interestIds.add(id);
            changed = true;
        }
        if (changed) {
            sections.clear();
            lastCenter = Long.MIN_VALUE;
            idle = 0;
            fullPass = false;
        }
        return all;
    }

    /** True once a complete scan has finished since the set of indexed blocks last changed. */
    public boolean settled() {
        return fullPass;
    }

    /** True if every block the spec matches is indexed. */
    public boolean covers(String spec) {
        Set<Block> wanted = BlockSpec.blocks(spec);
        if (wanted.isEmpty()) {
            return false;
        }
        for (Block b : wanted) {
            if (!interest.containsKey(b)) {
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
            fullPass = false;
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
                fullPass = true;
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

    /** Rescans the 3x3 chunks (5 sections tall) around the origin, at most once every few ticks. */
    private void refreshAround(BlockPos origin) {
        ClientLevel lv = level;
        if (lv == null || interest.isEmpty()) {
            return;
        }
        long now = lv.getGameTime();
        if (now - lastRefreshTick < REFRESH_EVERY_TICKS) {
            return;
        }
        lastRefreshTick = now;
        int pcx = origin.getX() >> 4;
        int pcz = origin.getZ() >> 4;
        int psy = Math.floorDiv(origin.getY(), 16);
        int lo = Math.max(psy - 2, lv.getMinSectionY());
        int hi = Math.min(psy + 2, lv.getMaxSectionY());
        for (int cx = pcx - 1; cx <= pcx + 1; cx++) {
            for (int cz = pcz - 1; cz <= pcz + 1; cz++) {
                for (int sy = lo; sy <= hi; sy++) {
                    scan(cx, sy, cz);
                }
            }
        }
    }

    /** Nearest indexed blocks matching the spec, closest first. Callers re-verify against the live world. */
    public List<BlockPos> find(String spec, BlockPos origin, double maxDist, int limit) {
        refreshAround(origin);
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