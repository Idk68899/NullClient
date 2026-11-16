package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.plan.Inv;
import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;

/**
 * Fights mobs only, like a player would: aims at the mob with the camera, closes in, and swings only when its own
 * swing timer has elapsed (about 13 ticks for a sword, 24 for an axe, 6 bare-handed), the attack cooldown is full,
 * the mob is within normal reach, nothing blocks the line, and the aim is on it. Never targets players. No
 * automatic hitting, no extended reach.
 */
public final class Combat {
    public static volatile boolean enabled = true;

    private static final double REACH = 2.9;
    private static final double THREAT_RANGE = 4.5;
    private static final int GIVE_UP_TICKS = 500;
    private static final Set<String> LEAVE_ALONE = Set.of("enderman", "zombified_piglin", "piglin");
    private static final Map<String, Integer> WEAPONS = Map.ofEntries(
            Map.entry("netherite_sword", 10), Map.entry("diamond_sword", 9), Map.entry("iron_sword", 8),
            Map.entry("stone_sword", 7), Map.entry("golden_sword", 6), Map.entry("wooden_sword", 5),
            Map.entry("netherite_axe", 4), Map.entry("diamond_axe", 4), Map.entry("iron_axe", 3),
            Map.entry("stone_axe", 3), Map.entry("golden_axe", 2), Map.entry("wooden_axe", 2));

    private LivingEntity target;
    private int engaged;
    private int sinceAttack = 99;

    public boolean active() {
        return target != null;
    }

    public void clear() {
        target = null;
        engaged = 0;
    }

    static String idOf(LivingEntity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    static Vec3 nearestPoint(Vec3 eye, AABB b) {
        return new Vec3(Mth.clamp(eye.x, b.minX, b.maxX), Mth.clamp(eye.y, b.minY, b.maxY), Mth.clamp(eye.z, b.minZ, b.maxZ));
    }

    /** True if nothing solid is between the player's eyes and the closest point of the entity. */
    public static boolean sees(ClientLevel level, LocalPlayer p, LivingEntity e) {
        Vec3 eye = p.getEyePosition();
        Vec3 near = nearestPoint(eye, e.getBoundingBox());
        return level.clip(new ClipContext(eye, near, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }

    /** A mob that just hurt us, or a hostile mob close by with a clear line. */
    private LivingEntity findThreat(LocalPlayer p, ClientLevel level) {
        LivingEntity by = p.getLastHurtByMob();
        if (p.hurtTime > 0 && by != null && by.isAlive() && !(by instanceof Player) && p.distanceTo(by) < 12.0f) {
            return by;
        }
        LivingEntity best = null;
        double bd = THREAT_RANGE * THREAT_RANGE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(THREAT_RANGE))) {
            if (e == p || e instanceof Player || !e.isAlive() || e.getType().getCategory() != MobCategory.MONSTER) {
                continue;
            }
            if (LEAVE_ALONE.contains(idOf(e))) {
                continue;
            }
            double d = e.distanceToSqr(p);
            if (d < bd && sees(level, p, e)) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private static void equipWeapon(LocalPlayer p) {
        Inventory inv = p.getInventory();
        int best = -1;
        int rank = 0;
        for (int i = 0; i < 9; i++) {
            int r = WEAPONS.getOrDefault(Inv.idOf(inv.getItem(i)), 0);
            if (r > rank) {
                rank = r;
                best = i;
            }
        }
        if (best >= 0 && best != inv.getSelectedSlot()) {
            inv.setSelectedSlot(best);
        }
    }

    /** Ticks to wait between swings for the item in hand. */
    private static int swingGap(LocalPlayer p) {
        Inventory inv = p.getInventory();
        String id = Inv.idOf(inv.getItem(inv.getSelectedSlot()));
        if (id.endsWith("_sword")) {
            return 13;
        }
        if (id.endsWith("_axe")) {
            return 24;
        }
        return 6;
    }

    /**
     * One tick of fighting. With {@code forced} it fights that mob (used when hunting for drops); otherwise only
     * when something threatens us. Returns true if it took control of the player this tick.
     */
    public boolean tick(Blackboard bb, LivingEntity forced) {
        LocalPlayer p = bb.get(AIKeys.PLAYER);
        ClientLevel level = bb.get(AIKeys.LEVEL);
        Minecraft mc = Minecraft.getInstance();
        if (p == null || level == null || (!enabled && forced == null)) {
            return false;
        }
        if (target != null && (!target.isAlive() || target.isRemoved() || p.distanceTo(target) > 24.0f || ++engaged > GIVE_UP_TICKS)) {
            if (target != null && !target.isAlive()) {
                RunLog.add("killed " + idOf(target));
            }
            clear();
        }
        if (forced != null) {
            if (target != forced) {
                target = forced;
                engaged = 0;
            }
        } else if (target == null) {
            LivingEntity t = findThreat(p, level);
            if (t != null) {
                target = t;
                engaged = 0;
                RunLog.add("fighting " + idOf(t) + " at " + t.blockPosition().toShortString());
            }
        }
        if (target == null) {
            return false;
        }
        fight(bb, p, level, mc);
        return true;
    }

    private void fight(Blackboard bb, LocalPlayer p, ClientLevel level, Minecraft mc) {
        sinceAttack++;
        Vec3 eye = p.getEyePosition();
        AABB box = target.getBoundingBox();
        double reach = eye.distanceTo(nearestPoint(eye, box));
        Vec3 aim = box.getCenter();
        double dx = aim.x - p.getX();
        double dy = aim.y - eye.y;
        double dz = aim.z - p.getZ();
        double hd = Math.hypot(dx, dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, hd));
        bb.set(AIKeys.YAW, yaw);
        bb.set(AIKeys.PITCH, pitch);
        equipWeapon(p);

        boolean los = sees(level, p, target);
        int actions = 0;
        if (hd > 0.01) {
            InputOverride.setDirection(dx / hd, dz / hd);
        }
        if (reach > REACH - 0.4 || !los) {
            actions |= InputOverride.FORWARD;
            if (reach > 4.5) {
                actions |= InputOverride.SPRINT;
            }
            if (p.horizontalCollision && p.onGround()) {
                actions |= InputOverride.JUMP;
            }
        } else if (idOf(target).equals("creeper") && reach < 2.2) {
            actions |= InputOverride.BACK;
        }
        AIKeys.addActions(bb, actions);

        boolean aimed = Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 9.0f && Math.abs(pitch - p.getXRot()) < 11.0f;
        if (reach <= REACH && los && aimed && mc.gameMode != null
                && sinceAttack >= swingGap(p) && p.getAttackStrengthScale(0.0f) >= 0.9f) {
            mc.gameMode.attack(p, target);
            p.swing(InteractionHand.MAIN_HAND);
            sinceAttack = 0;
        }
    }
}