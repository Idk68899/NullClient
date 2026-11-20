package dev.nullclient.gui;

import dev.nullclient.ai.world.Perception;
import dev.nullclient.ai.world.ScanReport;
import dev.nullclient.ai.world.Vision;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the AI sees: its low-resolution, texture-free view (flat colours from rays, mobs boxed and named), a top-down
 * radar of known blocks and mobs, and a scan check that compares the AI's index with the real world.
 */
final class ViewPage implements Page {
    private static final int RADIUS = 20;
    private static final int CELL = 3;

    private int[] radar;
    private final List<int[]> dots = new ArrayList<>();
    private final List<String> mobLines = new ArrayList<>();
    private final List<Integer> mobColors = new ArrayList<>();
    private long radarTick = Long.MIN_VALUE;
    private List<String> report = List.of();

    @Override
    public String title() {
        return "AI View";
    }

    @Override
    public int width() {
        return 340;
    }

    @Override
    public int height() {
        return 400;
    }

    private void computeRadar(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        long now = level.getGameTime();
        if (radar != null && now >= radarTick && now - radarTick < 10) {
            return;
        }
        radarTick = now;
        int n = RADIUS * 2 + 1;
        int[] out = new int[n * n];
        BlockPos o = p.blockPosition();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int topY = Math.min(level.getMaxY(), o.getY() + 8);
        int botY = Math.max(level.getMinY(), o.getY() - 32);
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                int x = o.getX() + dx;
                int z = o.getZ() + dz;
                int color = 0;
                if (level.hasChunk(x >> 4, z >> 4)) {
                    for (int y = topY; y >= botY; y--) {
                        mp.set(x, y, z);
                        BlockState s = level.getBlockState(mp);
                        if (s.isAir()) {
                            continue;
                        }
                        if (Vision.enabled() && !Vision.allowed(mp)) {
                            color = 0xFF14141C;
                        } else {
                            double f = Math.max(0.6, Math.min(1.3, 1.0 + (y - o.getY()) * 0.03));
                            int base = Perception.colorOf(s);
                            int r = Math.min(255, (int) (((base >> 16) & 0xFF) * f));
                            int g = Math.min(255, (int) (((base >> 8) & 0xFF) * f));
                            int b = Math.min(255, (int) ((base & 0xFF) * f));
                            color = 0xFF000000 | (r << 16) | (g << 8) | b;
                        }
                        break;
                    }
                }
                out[(dz + RADIUS) * n + dx + RADIUS] = color;
            }
        }
        radar = out;

        dots.clear();
        mobLines.clear();
        mobColors.clear();
        List<LivingEntity> near = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(RADIUS + 12)));
        near.remove(p);
        near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        for (LivingEntity e : near) {
            if (!e.isAlive() || !Vision.knowsMob(e)) {
                continue;
            }
            boolean hostile = e.getType().getCategory() == MobCategory.MONSTER;
            int color = e instanceof Player ? 0xFF55AAFF : hostile ? 0xFFFF4040 : 0xFF55DD66;
            dots.add(new int[]{(int) Math.round(e.getX() - p.getX()), (int) Math.round(e.getZ() - p.getZ()), color});
            if (mobLines.size() < 8) {
                String name = e instanceof Player pl ? pl.getName().getString() : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
                mobLines.add(name + " " + Math.round(e.distanceTo(p)) + "m");
                mobColors.add(color);
            }
        }
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        ui.text("What the AI sees - low resolution, no textures", x, y, Theme.accent());
        y += 14;
        int half = (w - 4) / 2;
        ui.button("Vision: " + (Vision.enabled() ? "ON (only what it sees)" : "OFF (reads everything)"), x, y, half, 18,
                () -> Ui.cmd("vision " + (Vision.enabled() ? "off" : "on")));
        ui.button("Scan check", x + half + 4, y, half, 18, () -> report = ScanReport.run());
        y += 24;

        if (mc.level == null || mc.player == null) {
            ui.text("Join a world to see the view.", x, y, Theme.MUTED);
            return;
        }
        Perception.request();
        Perception.update(mc);
        Perception.Frame f = Perception.frame();
        if (f == null) {
            ui.text("waiting for the first frame...", x, y, Theme.MUTED);
        } else {
            int cell = Math.max(2, Math.min(10, w / Perception.W));
            int gw = cell * Perception.W;
            int gh = cell * Perception.H;
            ui.clip(x, y, gw, gh);
            for (int r = 0; r < Perception.H; r++) {
                int start = 0;
                while (start < Perception.W) {
                    int c = f.colors()[r * Perception.W + start];
                    int end = start + 1;
                    while (end < Perception.W && f.colors()[r * Perception.W + end] == c) {
                        end++;
                    }
                    ui.fill(x + start * cell, y + r * cell, (end - start) * cell, cell, c);
                    start = end;
                }
            }
            for (Perception.Mob m : f.mobs()) {
                int pw = Math.max(cell, m.cw() * cell);
                int ph = Math.max(cell, m.ch() * cell);
                int bx = x + m.cx() * cell + cell / 2 - pw / 2;
                int by = y + m.cy() * cell + cell / 2 - ph / 2;
                ui.fill(bx, by, pw, 1, m.color());
                ui.fill(bx, by + ph - 1, pw, 1, m.color());
                ui.fill(bx, by, 1, ph, m.color());
                ui.fill(bx + pw - 1, by, 1, ph, m.color());
                ui.label(m.name() + " " + m.dist() + "m", bx, by - 9, 110, m.color());
            }
            ui.fill(x + gw / 2 - 3, y + gh / 2, 7, 1, 0xFFFFFFFF);
            ui.fill(x + gw / 2, y + gh / 2 - 3, 1, 7, 0xFFFFFFFF);
            ui.unclip();
            y += gh + 4;
            ui.label("rays that hit blocks: " + f.hits() + "/" + (Perception.W * Perception.H) + ", mobs in view: " + f.mobs().size()
                    + (Vision.enabled() ? ", blocks remembered: " + Vision.size() : ""), x, y, w, Theme.MUTED);
            y += 14;
        }

        computeRadar(mc);
        int n = RADIUS * 2 + 1;
        int rs = n * CELL;
        ui.text("Radar (top-down" + (Vision.enabled() ? ", only seen blocks" : "") + ")", x, y, Theme.accent());
        y += 12;
        ui.fill(x, y, rs, rs, 0xFF0A0D16);
        for (int r = 0; r < n; r++) {
            int start = 0;
            while (start < n) {
                int c = radar[r * n + start];
                int end = start + 1;
                while (end < n && radar[r * n + end] == c) {
                    end++;
                }
                if (c != 0) {
                    ui.fill(x + start * CELL, y + r * CELL, (end - start) * CELL, CELL, c);
                }
                start = end;
            }
        }
        for (int[] d : dots) {
            int px = x + (d[0] + RADIUS) * CELL;
            int py = y + (d[1] + RADIUS) * CELL;
            if (px >= x && px < x + rs - CELL && py >= y && py < y + rs - CELL) {
                ui.fill(px - 1, py - 1, CELL + 2, CELL + 2, d[2]);
            }
        }
        double yawRad = Math.toRadians(mc.player.getYRot());
        int cx = x + RADIUS * CELL;
        int cy = y + RADIUS * CELL;
        ui.fill(cx - 1, cy - 1, CELL + 2, CELL + 2, 0xFFFFFFFF);
        ui.fill(cx + (int) Math.round(-Math.sin(yawRad) * 6) + 1, cy + (int) Math.round(Math.cos(yawRad) * 6) + 1, 2, 2, 0xFFFFFFFF);

        int lx = x + rs + 8;
        int ly = y;
        ui.text("Nearby mobs", lx, ly, Theme.accent());
        ly += 12;
        if (mobLines.isEmpty()) {
            ui.label("none known", lx, ly, w - rs - 8, Theme.MUTED);
        }
        for (int i = 0; i < mobLines.size(); i++) {
            ui.label(mobLines.get(i), lx, ly + i * 11, w - rs - 8, mobColors.get(i));
        }
        y += rs + 8;

        if (!report.isEmpty()) {
            ui.text("Scan check", x, y, Theme.accent());
            y += 12;
            for (String l : report) {
                ui.label(l, x, y, w, l.startsWith("verdict") ? Theme.WARN : Theme.TEXT);
                y += 11;
            }
        }
    }
}