package dev.nullclient.ai.world;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Block matcher. Comma-separated; "=id" is exact, a bare "id" also matches "*_id" and "id_*".
 * Specs are parsed once and resolved to a set of Block objects, so per-block tests do no string work.
 */
public final class BlockSpec {
    private static final Map<String, String[]> PARTS = new HashMap<>();
    private static final Map<String, Set<Block>> BLOCKS = new HashMap<>();

    private BlockSpec() {
    }

    public static boolean test(String id, String spec) {
        String[] parts = PARTS.get(spec);
        if (parts == null) {
            parts = spec.split(",");
            PARTS.put(spec, parts);
        }
        for (String part : parts) {
            if (part.startsWith("=")) {
                if (id.equals(part.substring(1))) {
                    return true;
                }
            } else if (id.equals(part) || id.endsWith("_" + part) || id.startsWith(part + "_")) {
                return true;
            }
        }
        return false;
    }

    /** All blocks the spec matches, resolved once per spec. */
    public static Set<Block> blocks(String spec) {
        Set<Block> cached = BLOCKS.get(spec);
        if (cached != null) {
            return cached;
        }
        Set<Block> out = new HashSet<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            if (test(BuiltInRegistries.BLOCK.getKey(b).getPath(), spec)) {
                out.add(b);
            }
        }
        BLOCKS.put(spec, out);
        return out;
    }

    /** Fast test of a world block against a spec. */
    public static boolean matches(BlockState state, String spec) {
        return !state.isAir() && blocks(spec).contains(state.getBlock());
    }
}