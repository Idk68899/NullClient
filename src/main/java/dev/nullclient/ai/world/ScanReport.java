package dev.nullclient.ai.world;

import dev.nullclient.ai.AIRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compares what the world index reports with what really exists around you, to tell a bug from an empty area. */
public final class ScanReport {
    private static final int RXZ = 24;
    private static final int DOWN = 40;
    private static final int UP = 10;
    private static final String[][] ORES = {
            {"coal", "coal_ore"}, {"iron", "iron_ore"}, {"gold", "gold_ore"}, {"redstone", "redstone_ore"},
            {"lapis", "lapis_ore"}, {"diamond", "diamond_ore"}, {"emerald", "emerald_ore"}};

    private ScanReport() {
    }

    public static List<String> run() {
        List<String> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            out.add("not in a world");
            return out;
        }
        BlockPos o = p.blockPosition();
        int loaded = 0;
        int total = 0;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                total++;
                if (level.hasChunk((o.getX() >> 4) + dx, (o.getZ() >> 4) + dz)) {
                    loaded++;
                }
            }
        }
        out.add("you are at " + o.toShortString() + "; chunks loaded around you: " + loaded + "/" + total);
        out.add("engine: " + (AIRuntime.started() ? "on" : "OFF (type .ai start - the index is empty until then)")
                + ", index " + (WorldScanner.INSTANCE.settled() ? "complete" : "still scanning")
                + ", vision " + (Vision.enabled() ? "ON (only blocks it has seen count)" : "off"));

        Map<Block, String> which = new HashMap<>();
        for (String[] w : ORES) {
            for (Block b : BlockSpec.blocks(w[1])) {
                which.put(b, w[0]);
            }
        }
        for (Block b : BlockSpec.blocks("=stone")) {
            which.put(b, "stone");
        }
        for (Block b : BlockSpec.blocks("=deepslate")) {
            which.put(b, "deepslate");
        }
        Map<String, Integer> direct = new LinkedHashMap<>();
        for (String[] w : ORES) {
            direct.put(w[0], 0);
        }
        direct.put("stone", 0);
        direct.put("deepslate", 0);
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int minY = Math.max(level.getMinY(), o.getY() - DOWN);
        int maxY = Math.min(level.getMaxY(), o.getY() + UP);
        for (int dx = -RXZ; dx <= RXZ; dx++) {
            for (int dz = -RXZ; dz <= RXZ; dz++) {
                int x = o.getX() + dx;
                int z = o.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    mp.set(x, y, z);
                    String n = which.get(level.getBlockState(mp).getBlock());
                    if (n != null) {
                        direct.merge(n, 1, Integer::sum);
                    }
                }
            }
        }
        StringBuilder real = new StringBuilder("really in the world:");
        int oreReal = 0;
        for (Map.Entry<String, Integer> e : direct.entrySet()) {
            real.append(' ').append(e.getKey()).append(' ').append(e.getValue()).append(',');
            if (!e.getKey().equals("stone") && !e.getKey().equals("deepslate")) {
                oreReal += e.getValue();
            }
        }
        out.add(real.toString());

        StringBuilder idx = new StringBuilder("the index says (96 blocks):");
        int oreIndex = 0;
        for (String[] w : ORES) {
            int n = WorldScanner.INSTANCE.find(w[1], o, 96, 5000).size();
            oreIndex += n;
            idx.append(' ').append(w[0]).append(' ').append(n).append(',');
        }
        out.add(idx.toString());

        if (!AIRuntime.started()) {
            out.add("verdict: start the engine with .ai start, then check again");
        } else if (oreReal > 0 && oreIndex == 0) {
            out.add(Vision.enabled()
                    ? "verdict: ores exist but vision is ON, so unseen ones are hidden on purpose - turn Vision off to see them"
                    : "verdict: the index is missing ores that really exist - that is a bug, please send me these lines");
        } else if (oreReal == 0) {
            out.add("verdict: no ores within range - probably a lobby, a high island or too shallow; dig down or explore");
        } else {
            out.add("verdict: the index and the world agree");
        }
        return out;
    }
}