package dev.nullclient.gui;

import dev.nullclient.ai.AIRuntime;
import dev.nullclient.ai.nav.WorldView;
import dev.nullclient.ai.task.Armor;
import dev.nullclient.ai.task.CoursePreview;
import dev.nullclient.ai.world.Perception;
import dev.nullclient.core.course.Course;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tools: basic commands, parkour courses (map, checkpoints, route check, run), and the screen-off mode. */
final class ToolsPage implements Page {
    private static final String[] MODES = {"Basics", "Parkour", "Screen"};
    private static final int GW = Perception.W;
    private static final int GH = Perception.H;
    private static final double FOV_X = 100.0;
    private static final double FOV_Y = FOV_X * GH / GW;
    private static final int ARROW = 0xFFFF8A1F;
    private static final int JUMP = 0xFFFFD040;

    private final Field tell = new Field(120).hint("get down, get out, stay, get iron armor...");
    private final Field newCourse = new Field(32).hint("new course name");
    private final CourseMap map = new CourseMap();
    private int mode;
    private Course course;
    private CoursePreview preview;
    private String note = "";

    ToolsPage() {
        tell.onEnter = this::goTell;
    }

    @Override
    public String title() {
        return "Tools";
    }

    @Override
    public int width() {
        return 340;
    }

    @Override
    public int height() {
        return 430;
    }

    private static void run(String cmd) {
        if (!AIRuntime.started()) {
            Ui.cmd("start");
        }
        Ui.cmd(cmd);
    }

    private void goTell() {
        String t = tell.text.trim();
        if (!t.isEmpty()) {
            run(t);
        }
    }

    @Override
    public void draw(Ui ui, int x, int y, int w, int h) {
        int third = (w - 8) / 3;
        for (int i = 0; i < MODES.length; i++) {
            int m = i;
            ui.button((i == mode ? "> " : "") + MODES[i], x + i * (third + 4), y, third, 18, () -> mode = m);
        }
        y += 24;
        switch (mode) {
            case 0 -> basics(ui, x, y, w);
            case 1 -> parkour(ui, x, y, w);
            default -> screen(ui, x, y, w);
        }
    }

    // ------------------------------------------------------------------ basics

    private void basics(Ui ui, int x, int y, int w) {
        int half = (w - 4) / 2;
        ui.text("Basic commands", x, y, Theme.accent());
        y += 12;
        String[][] cmds = {{"Get down", "down"}, {"Get out / surface", "up"}, {"Go home", "home"}, {"Go back", "back"}};
        for (int i = 0; i < cmds.length; i++) {
            String c = cmds[i][1];
            ui.button(cmds[i][0], x + (i % 2) * (half + 4), y + (i / 2) * 22, half, 18, () -> run(c));
        }
        y += 48;
        ui.button("Stop task", x, y, half, 18, () -> Ui.cmd("stop"));
        ui.button("Stop everything", x + half + 4, y, half, 18, () -> Ui.cmd("stopall"));
        y += 28;
        ui.text("Or type it:", x, y, Theme.MUTED);
        y += 12;
        ui.field(tell, x, y, w - 54, 20);
        ui.button("Run", x + w - 50, y, 50, 20, this::goTell);
        y += 28;
        ui.button("Auto-wear armor: " + (Armor.enabled ? "ON" : "OFF"), x, y, w, 18, () -> Armor.enabled = !Armor.enabled);
        ui.fill(x, y, 3, 18, Armor.enabled ? Theme.GOOD : Theme.BAD);
        y += 24;
        ui.label("Examples: get down | get out | stay | get a full set of iron armor", x, y, w, Theme.MUTED);
    }

    // ------------------------------------------------------------------ screen

    private void screen(Ui ui, int x, int y, int w) {
        ui.text("Screen off while the AI works", x, y, Theme.accent());
        y += 14;
        ui.button("Turn the screen off while a task runs: " + (ScreenSaver.enabled ? "ON" : "OFF"), x, y, w, 20, () -> ScreenSaver.enabled = !ScreenSaver.enabled);
        ui.fill(x, y, 3, 20, ScreenSaver.enabled ? Theme.GOOD : Theme.BAD);
        y += 26;
        ui.button("Turn the screen off now", x, y, w, 20, ScreenSaver::screenOffNow);
        y += 28;
        ui.label("It comes back by itself when the task finishes or fails.", x, y, w, Theme.MUTED);
        y += 11;
        ui.label("On the black screen: Screen on (or ESC / END) turns it back on.", x, y, w, Theme.MUTED);
        y += 11;
        ui.label("Waking it keeps it on for the rest of that task.", x, y, w, Theme.MUTED);
        y += 16;
        ui.label(ScreenSaver.hookRan ? "render hook: active - the world is not drawn while it is off"
                : "render hook: not active (yet) - the display goes black but the world is still drawn", x, y, w,
                ScreenSaver.hookRan ? Theme.GOOD : Theme.WARN);
    }

    // ------------------------------------------------------------------ parkour

    private void select(Course c) {
        course = c;
        preview = null;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            map.recenter(p.blockPosition());
        }
    }

    private void add(BlockPos pos, String how) {
        course.points.add(new Course.Pt(pos));
        Course.save();
        preview = null;
        Minecraft mc = Minecraft.getInstance();
        boolean ok = mc.level != null && new WorldView(mc.level).canStand(pos.getX(), pos.getY(), pos.getZ());
        note = "checkpoint " + course.points.size() + " at " + pos.toShortString() + " (" + how + ")"
                + (ok ? "" : " - not a standable spot, check the route");
    }

    private void markLook(Minecraft mc) {
        LocalPlayer p = mc.player;
        Vec3 eye = p.getEyePosition();
        BlockHitResult h = mc.level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(96.0)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (h.getType() != HitResult.Type.BLOCK) {
            note = "nothing in sight within 96 blocks";
            return;
        }
        add(h.getBlockPos().above(), "where you look");
    }

    private void tap(int mx, int my, int mapX, int mapY, int cell) {
        int c = (mx - mapX) / cell;
        int r = (my - mapY) / cell;
        if (mx < mapX || my < mapY || c >= CourseMap.COLS || r >= CourseMap.ROWS) {
            return;
        }
        int top = map.top(c, r);
        if (top == CourseMap.NONE) {
            note = "nothing loaded there";
            return;
        }
        add(new BlockPos(map.worldX(c), top + 1, map.worldZ(r)), "map");
    }

    private static int[] project(Vec3 eye, float yaw, float pitch, BlockPos b, int x, int y, int cell) {
        Vec3 c = Vec3.atCenterOf(b);
        double dx = c.x - eye.x;
        double dy = c.y - eye.y;
        double dz = c.z - eye.z;
        double offY = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) - yaw);
        double offP = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))) - pitch;
        if (Math.abs(offY) > FOV_X / 2 || Math.abs(offP) > FOV_Y / 2) {
            return null;
        }
        return new int[]{x + (int) ((offY / FOV_X + 0.5) * GW * cell), y + (int) ((offP / FOV_Y + 0.5) * GH * cell)};
    }

    private int camera(Ui ui, Minecraft mc, int x, int y, List<BlockPos> pts) {
        Perception.request();
        Perception.update(mc);
        Perception.Frame f = Perception.frame();
        int cell = 5;
        int gw = cell * GW;
        int gh = cell * GH;
        ui.text("Camera view (checkpoints and arrows)", x, y, Theme.accent());
        y += 12;
        ui.clip(x, y, gw, gh);
        if (f != null) {
            for (int r = 0; r < GH; r++) {
                int start = 0;
                while (start < GW) {
                    int c = f.colors()[r * GW + start];
                    int end = start + 1;
                    while (end < GW && f.colors()[r * GW + end] == c) {
                        end++;
                    }
                    ui.fill(x + start * cell, y + r * cell, (end - start) * cell, cell, c);
                    start = end;
                }
            }
        }
        LocalPlayer p = mc.player;
        Vec3 eye = p.getEyePosition();
        int[] prev = null;
        for (int i = 0; i < pts.size(); i++) {
            int[] s = project(eye, p.getYRot(), p.getXRot(), pts.get(i), x, y, cell);
            if (s != null) {
                int col = i == 0 ? Theme.GOOD : i == pts.size() - 1 ? Theme.BAD : 0xFF4AEDD9;
                ui.fill(s[0] - 3, s[1] - 3, 7, 7, col);
                ui.text(Integer.toString(i + 1), s[0] + 5, s[1] - 9, col);
                if (prev != null) {
                    Shapes.arrow(ui, prev[0], prev[1], s[0], s[1], ARROW);
                }
            }
            prev = s;
        }
        ui.unclip();
        y += gh + 2;
        if (!pts.isEmpty() && project(eye, p.getYRot(), p.getXRot(), pts.get(0), x, y, cell) == null) {
            Vec3 c = Vec3.atCenterOf(pts.get(0));
            double off = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-(c.x - eye.x), c.z - eye.z)) - p.getYRot());
            ui.label("checkpoint 1 is to your " + (off > 0 ? "right" : "left") + " (turn " + Math.round(Math.abs(off)) + " degrees)", x, y, gw, Theme.MUTED);
            y += 11;
        }
        return y + 4;
    }

    private void parkour(Ui ui, int x, int y, int w) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (mc.level == null || p == null) {
            ui.text("Join a world first.", x, y, Theme.MUTED);
            return;
        }
        ui.text("Courses", x, y, Theme.accent());
        y += 12;
        for (Course c : new ArrayList<>(Course.all())) {
            ui.button((c == course ? "> " : "") + c.name + "  (" + c.points.size() + " checkpoints)", x, y, w - 26, 18, () -> select(c));
            ui.button("x", x + w - 22, y, 22, 18, () -> {
                Course.remove(c);
                if (course == c) {
                    course = null;
                    preview = null;
                }
            });
            y += 22;
        }
        ui.field(newCourse, x, y, w - 74, 20);
        ui.button("Create", x + w - 70, y, 70, 20, () -> {
            try {
                select(Course.add(newCourse.text));
                newCourse.text = "";
            } catch (IllegalArgumentException e) {
                note = e.getMessage();
            }
        });
        y += 26;
        if (course == null) {
            ui.label(note.isEmpty() ? "Create or pick a course, then tap the map to place checkpoints." : note, x, y, w, Theme.MUTED);
            return;
        }
        List<BlockPos> pts = course.positions();
        boolean running = Ui.nc().ai().activeAgent() != null;
        int half = (w - 4) / 2;
        ui.button("Mark my position", x, y, half, 18, () -> add(p.blockPosition(), "your position"));
        ui.button("Mark where I look", x + half + 4, y, half, 18, () -> markLook(mc));
        y += 22;
        int q = (w - 12) / 4;
        ui.button("Undo last", x, y, q, 18, () -> {
            if (!course.points.isEmpty()) {
                course.points.remove(course.points.size() - 1);
                Course.save();
                preview = null;
            }
        });
        ui.button("Clear", x + (q + 4), y, q, 18, () -> {
            course.points.clear();
            Course.save();
            preview = null;
        });
        ui.button("Check route", x + 2 * (q + 4), y, q, 18, () -> {
            if (pts.isEmpty()) {
                note = "add a checkpoint first";
            } else {
                preview = new CoursePreview(pts, p);
            }
        });
        ui.button(running ? "Stop" : "Run course", x + 3 * (q + 4), y, q, 18, () -> {
            if (running) {
                Ui.cmd("stopall");
            } else if (pts.isEmpty()) {
                note = "add a checkpoint first";
            } else {
                run("course " + course.name);
            }
        });
        y += 24;

        int bw = (w - 8 * 4) / 9;
        String[] labels = {"N", "S", "W", "E", "-", "+", "Y+", "Y-", "Me"};
        for (int i = 0; i < labels.length; i++) {
            int k = i;
            ui.button(labels[i], x + i * (bw + 4), y, bw, 16, () -> {
                int step = 8 * map.scale;
                switch (k) {
                    case 0 -> map.centerZ -= step;
                    case 1 -> map.centerZ += step;
                    case 2 -> map.centerX -= step;
                    case 3 -> map.centerX += step;
                    case 4 -> map.scale = Math.min(4, map.scale + 1);
                    case 5 -> map.scale = Math.max(1, map.scale - 1);
                    case 6 -> map.refY += 8;
                    case 7 -> map.refY -= 8;
                    default -> map.recenter(p.blockPosition());
                }
                map.dirty = true;
            });
        }
        y += 20;
        ui.label("1 cell = " + map.scale + " block(s), height slice around y " + map.refY + ".  Tap the map to add a checkpoint.", x, y, w, Theme.MUTED);
        y += 12;

        if (map.dirty || ui.frame - map.computedAt > 150) {
            map.compute(mc.level, ui.frame);
        }
        int cell = Math.max(3, Math.min(8, w / CourseMap.COLS));
        int mw = cell * CourseMap.COLS;
        int mh = cell * CourseMap.ROWS;
        int mapX = x;
        int mapY = y;
        ui.clip(mapX, mapY, mw, mh);
        for (int r = 0; r < CourseMap.ROWS; r++) {
            int start = 0;
            while (start < CourseMap.COLS) {
                int c = map.colors[r * CourseMap.COLS + start];
                int end = start + 1;
                while (end < CourseMap.COLS && map.colors[r * CourseMap.COLS + end] == c) {
                    end++;
                }
                ui.fill(mapX + start * cell, mapY + r * cell, (end - start) * cell, cell, c);
                start = end;
            }
        }
        if (preview != null) {
            if (!preview.finished()) {
                preview.step(mc.level);
            }
            for (CoursePreview.Leg leg : preview.legs()) {
                int[] last = null;
                for (int[] n : leg.nodes) {
                    int px = mapX + map.colOf(n[0]) * cell + cell / 2;
                    int py = mapY + map.rowOf(n[2]) * cell + cell / 2;
                    if (last != null) {
                        if (n[3] == 1) {
                            Shapes.arrow(ui, last[0], last[1], px, py, JUMP);
                        } else {
                            Shapes.line(ui, last[0], last[1], px, py, 0xFF55DD66, 1);
                        }
                    }
                    last = new int[]{px, py};
                }
            }
        }
        int[] prev = null;
        for (int i = 0; i < pts.size(); i++) {
            BlockPos b = pts.get(i);
            int px = mapX + map.colOf(b.getX()) * cell + cell / 2;
            int py = mapY + map.rowOf(b.getZ()) * cell + cell / 2;
            if (prev != null) {
                Shapes.arrow(ui, prev[0], prev[1], px, py, ARROW);
            }
            prev = new int[]{px, py};
        }
        for (int i = 0; i < pts.size(); i++) {
            BlockPos b = pts.get(i);
            int px = mapX + map.colOf(b.getX()) * cell + cell / 2;
            int py = mapY + map.rowOf(b.getZ()) * cell + cell / 2;
            int col = i == 0 ? Theme.GOOD : i == pts.size() - 1 ? Theme.BAD : 0xFF4AEDD9;
            ui.fill(px - 3, py - 3, 7, 7, col);
            ui.text(Integer.toString(i + 1), px + 5, py - 9, col);
        }
        int ppx = mapX + map.colOf(p.getBlockX()) * cell + cell / 2;
        int ppy = mapY + map.rowOf(p.getBlockZ()) * cell + cell / 2;
        ui.fill(ppx - 2, ppy - 2, 5, 5, 0xFFFFFFFF);
        double yaw = Math.toRadians(p.getYRot());
        ui.fill(ppx + (int) Math.round(-Math.sin(yaw) * 8), ppy + (int) Math.round(Math.cos(yaw) * 8), 3, 3, 0xFFFFFFFF);
        ui.unclip();
        ui.area(mapX, mapY, mw, mh, () -> tap(ui.mouseX(), ui.mouseY(), mapX, mapY, cell));
        y += mh + 6;

        y = camera(ui, mc, x, y, pts);

        ui.text("Checkpoints", x, y, Theme.accent());
        y += 12;
        if (pts.isEmpty()) {
            ui.text("none yet", x, y, Theme.MUTED);
            y += 12;
        }
        for (int i = 0; i < pts.size(); i++) {
            int idx = i;
            BlockPos b = pts.get(i);
            String info = (i + 1) + ".  " + b.getX() + " " + b.getY() + " " + b.getZ();
            if (i + 1 < pts.size()) {
                BlockPos n = pts.get(i + 1);
                info += String.format(Locale.ROOT, "   -> next %.0fm, %+d up", Math.hypot(n.getX() - b.getX(), n.getZ() - b.getZ()), n.getY() - b.getY());
            }
            ui.label(info, x, y + 5, w - 54, Theme.TEXT);
            ui.button("go", x + w - 52, y, 26, 18, () -> run("goto " + b.getX() + " " + b.getY() + " " + b.getZ()));
            ui.button("x", x + w - 24, y, 24, 18, () -> {
                course.points.remove(idx);
                Course.save();
                preview = null;
            });
            y += 22;
        }
        y += 4;

        if (preview != null) {
            ui.text(preview.finished() ? "Route check" : "Route check (working...)", x, y, Theme.accent());
            y += 12;
            List<CoursePreview.Leg> legs = preview.legs();
            for (int i = 0; i < legs.size(); i++) {
                CoursePreview.Leg leg = legs.get(i);
                String name = "to checkpoint " + (i + 1) + ": ";
                if (!leg.done) {
                    ui.label(name + "...", x, y, w, Theme.MUTED);
                    y += 11;
                    continue;
                }
                if (leg.ok) {
                    ui.label(name + "OK - " + (leg.nodes.size() - 1) + " steps, " + leg.jumps + " jump(s)", x, y, w, Theme.GOOD);
                    y += 11;
                } else {
                    ui.label(name + "BLOCKED - " + leg.problem, x, y, w, Theme.BAD);
                    y += 11;
                    for (String b : leg.blockers) {
                        ui.label("   in the way: " + b, x, y, w, Theme.WARN);
                        y += 11;
                    }
                }
            }
            y += 4;
        }
        ui.label(note, x, y, w, Theme.MUTED);
    }
}