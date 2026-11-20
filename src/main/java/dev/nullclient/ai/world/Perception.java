package dev.nullclient.ai.world;

import dev.nullclient.ai.task.Combat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The AI's low-resolution "screen": a 32x18 grid of rays cast from the player's eyes, each ray becoming one flat-colored
 * cell (no textures), plus the mobs in view with a clear line. It runs only while Vision is on or the AI View window is
 * open. Every block a ray hits is also reported to {@link Vision} as "seen".
 */
public final class Perception {
    public static final int W = 32;
    public static final int H = 18;
    public static final double RANGE = 32.0;
    public static final int SKY = 0xFF16213A;

    private static final double FOV_X = 100.0;
    private static final double FOV_Y = FOV_X * H / W;
    private static final long EVERY_TICKS = 5;
    private static final long WANT_MS = 1500;

    /** A mob in view: centre cell, size in cells, colour, name and distance in blocks. */
    public record Mob(int cx, int cy, int cw, int ch, int color, String name, int dist) {
    }

    public record Frame(int[] colors, List<Mob> mobs, long tick, int hits) {
    }

    private static final Map<Block, Integer> COLORS = new HashMap<>();
    private static volatile Frame frame;
    private static long lastCast = Long.MIN_VALUE;
    private static long wantedUntil;

    private Perception() {
    }

    /** Keeps the view updating for the next second and a half (called by the AI View window every frame). */
    public static void request() {
        wantedUntil = System.currentTimeMillis() + WANT_MS;
    }

    public static boolean wanted() {
        return Vision.enabled() || System.currentTimeMillis() < wantedUntil;
    }

    public static Frame frame() {
        return frame;
    }

    static int palette(String id) {
        if (id.contains("diamond_ore")) return 0xFF4AEDD9;
        if (id.contains("iron_ore")) return 0xFFD8AF93;
        if (id.contains("gold_ore")) return 0xFFFCEE4B;
        if (id.contains("coal_ore")) return 0xFF2B2B2B;
        if (id.contains("redstone_ore")) return 0xFFE02020;
        if (id.contains("lapis_ore")) return 0xFF2850C8;
        if (id.contains("emerald_ore")) return 0xFF17DD62;
        if (id.contains("copper_ore")) return 0xFFC77B58;
        if (id.contains("lava") || id.equals("magma_block")) return 0xFFFF6A00;
        if (id.contains("water")) return 0xFF3A62D8;
        if (id.contains("leaves")) return 0xFF2E7D32;
        if (id.endsWith("_log") || id.endsWith("_wood") || id.contains("stem")) return 0xFF7A5A33;
        if (id.endsWith("_planks")) return 0xFFB58B52;
        if (id.contains("grass")) return 0xFF5FA844;
        if (id.contains("dirt") || id.contains("mud") || id.contains("podzol") || id.equals("farmland")) return 0xFF7A5333;
        if (id.contains("sand")) return 0xFFDCCB8D;
        if (id.contains("gravel")) return 0xFF8A8580;
        if (id.contains("snow") || id.contains("ice")) return 0xFFEAF2FF;
        if (id.contains("netherrack") || id.contains("nether_brick")) return 0xFF6E2A2A;
        if (id.contains("deepslate")) return 0xFF4A4A52;
        if (id.contains("stone") || id.contains("cobble") || id.contains("andesite") || id.contains("diorite")
                || id.contains("granite") || id.contains("tuff")) return 0xFF8B8B8B;
        if (id.equals("crafting_table") || id.equals("furnace") || id.equals("chest")) return 0xFFE0A040;
        if (id.equals("bedrock")) return 0xFF222222;
        return 0xFF9A9AA6;
    }

    /** The flat colour a block is drawn with. */
    public static int colorOf(BlockState s) {
        Block b = s.getBlock();
        Integer c = COLORS.get(b);
        if (c == null) {
            c = palette(BuiltInRegistries.BLOCK.getKey(b).getPath());
            COLORS.put(b, c);
        }
        return c;
    }

    /** Darkens a colour with distance in 8 steps (steps keep neighbouring cells equal, so rows compress well). */
    static int shade(int argb, double dist) {
        int q = (int) Math.min(7, Math.max(0, dist / RANGE * 8.0));
        double f = 1.0 - q * 0.07;
        int r = (int) (((argb >> 16) & 0xFF) * f);
        int g = (int) (((argb >> 8) & 0xFF) * f);
        int b = (int) ((argb & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Casts the grid and finds the mobs in view. Throttled to once every few ticks; call from the game thread. */
    public static void update(Minecraft mc) {
        if (!wanted()) {
            return;
        }
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            return;
        }
        long now = level.getGameTime();
        if (now >= lastCast && now - lastCast < EVERY_TICKS) {
            return;
        }
        lastCast = now;
        Vec3 eye = p.getEyePosition();
        double yaw = p.getYRot();
        double pitch = p.getXRot();
        int[] colors = new int[W * H];
        int hits = 0;
        for (int cy = 0; cy < H; cy++) {
            double pp = Mth.clamp(pitch + ((cy + 0.5) / H - 0.5) * FOV_Y, -89.9, 89.9);
            for (int cx = 0; cx < W; cx++) {
                double yy = yaw + ((cx + 0.5) / W - 0.5) * FOV_X;
                Vec3 to = eye.add(Vision.dir(yy, pp).scale(RANGE));
                BlockHitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
                int i = cy * W + cx;
                if (hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos bp = hit.getBlockPos();
                    Vision.markSeen(bp.asLong(), now);
                    colors[i] = shade(colorOf(level.getBlockState(bp)), eye.distanceTo(hit.getLocation()));
                    hits++;
                } else {
                    colors[i] = SKY;
                }
            }
        }

        List<Mob> mobs = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(RANGE))) {
            if (e == p || !e.isAlive()) {
                continue;
            }
            Vec3 c = e.getBoundingBox().getCenter();
            double dx = c.x - eye.x;
            double dy = c.y - eye.y;
            double dz = c.z - eye.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > RANGE || dist < 0.3) {
                continue;
            }
            double offY = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) - yaw);
            double offP = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))) - pitch;
            if (Math.abs(offY) > FOV_X / 2 + 4 || Math.abs(offP) > FOV_Y / 2 + 4 || !Combat.sees(level, p, e)) {
                continue;
            }
            Vision.noteMob(e.getId(), now);
            int cx = (int) ((offY / FOV_X + 0.5) * W);
            int cy = (int) ((offP / FOV_Y + 0.5) * H);
            double angW = Math.toDegrees(2.0 * Math.atan(0.5 * e.getBbWidth() / dist));
            double angH = Math.toDegrees(2.0 * Math.atan(0.5 * e.getBbHeight() / dist));
            int cw = Math.max(1, (int) Math.round(angW / FOV_X * W));
            int ch = Math.max(1, (int) Math.round(angH / FOV_Y * H));
            int color;
            String name;
            if (e instanceof Player pl) {
                color = 0xFF55AAFF;
                name = pl.getName().getString();
            } else {
                color = e.getType().getCategory() == MobCategory.MONSTER ? 0xFFFF4040 : 0xFF55DD66;
                name = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            }
            mobs.add(new Mob(cx, cy, cw, ch, color, name, (int) Math.round(dist)));
        }
        frame = new Frame(colors, mobs, now, hits);
    }
}