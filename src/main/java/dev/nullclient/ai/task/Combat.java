package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.plan.Inv;
import dev.nullclient.core.input.InputOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;

/**
 * Fights mobs only, like a player would, with normal vanilla reach and cooldowns:
 * <ul>
 * <li>aims with the camera and closes in; swings only after its own swing timer (about 13 ticks for a sword, 24 for an
 * axe, 6 bare-handed) and a full attack cooldown, with a clear line and the aim on the mob;</li>
 * <li>critical hits: at healthy HP it jumps and hits while falling (no sprint, as crits require);</li>
 * <li>w-tap: at low HP it sprint-hits for knockback and releases forward for two ticks after each hit;</li>
 * <li>shield: moves a shield to the offhand and raises it against ranged mobs and fusing creepers;</li>
 * <li>creepers: places a block between you and an approaching creeper, and backs off when it is too close.</li>
 * </ul>
 * Never targets players. No automatic hitting, no extended reach.
 */
public final class Combat {
    public static volatile boolean enabled = true;

    private static final double REACH = 2.9;
    private static final double THREAT_RANGE = 4.5;
    private static final int GIVE_UP_TICKS = 500;
    private static final Set<String> LEAVE_ALONE = Set.of("enderman", "zombified_piglin", "piglin");
    private static final Set<String> RANGED = Set.of("skeleton", "stray", "bogged", "blaze", "pillager", "witch");
    private static final Map<String, Integer> WEAPONS = Map.ofEntries(
            Map.entry("netherite_sword", 10), Map.entry("diamond_sword", 9), Map.entry("iron_sword", 8),
            Map.entry("stone_sword", 7), Map.entry("golden_sword", 6), Map.entry("wooden_sword", 5),
            Map.entry("netherite_axe", 4), Map.entry("diamond_axe", 4), Map.entry("iron_axe", 3),
            Map.entry("stone_axe", 3), Map.entry("golden_axe", 2), Map.entry("wooden_axe", 2));

    private LivingEntity target;
    private int engaged;
    private int sinceAttack = 99;
    private int wtap;
    private int critWait;
    private boolean jumped;
    private boolean shieldHeld;
    private int swapCooldown;
    private int blockCooldown;
    private int placeTicks;

    public boolean active() {
        return target != null;
    }

    public void clear() {
        target = null;
        engaged = 0;
        jumped = false;
        wtap = 0;
        releaseShield(Minecraft.getInstance());
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

    /** True while a creeper is fusing (looked up by name so it does not depend on the mob's package). */
    private static boolean swelling(LivingEntity e) {
        try {
            Object r = e.getClass().getMethod("getSwellDir").invoke(e);
            return r instanceof Integer i && i > 0;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
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

    // ------------------------------------------------------------------ shield

    private void releaseShield(Minecraft mc) {
        if (shieldHeld) {
            mc.options.keyUse.setDown(false);
            shieldHeld = false;
        }
    }

    private void manageShield(Minecraft mc, LocalPlayer p, boolean want) {
        boolean has = Inv.idOf(p.getOffhandItem()).equals("shield");
        if (want && !has && swapCooldown == 0 && mc.gameMode != null) {
            Inventory inv = p.getInventory();
            for (Slot sl : p.inventoryMenu.slots) {
                if (sl.container == inv && sl.hasItem() && Inv.idOf(sl.getItem()).equals("shield")) {
                    mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, sl.index, 40, ContainerInput.SWAP, p);
                    RunLog.add("moved the shield to the offhand");
                    break;
                }
            }
            swapCooldown = 60;
            return;
        }
        if (want && has) {
            mc.options.keyUse.setDown(true);
            shieldHeld = true;
        } else {
            releaseShield(mc);
        }
    }

    // ------------------------------------------------------------------ creeper block

    /** Puts a block on the cell between us and the creeper. Returns true while it is busy placing. */
    private boolean wallFront(Blackboard bb, LocalPlayer p, ClientLevel level, double dx, double dz, double hd) {
        if (hd < 0.01) {
            return false;
        }
        BlockPos o = p.blockPosition();
        BlockPos c = o.offset((int) Math.round(dx / hd), 0, (int) Math.round(dz / hd));
        if (c.equals(o)) {
            return false;
        }
        boolean free = level.getBlockState(c).getCollisionShape(level, c).isEmpty() && level.getBlockState(c).getFluidState().isEmpty();
        if (!free) {
            if (placeTicks > 0) {
                blockCooldown = 80;
                placeTicks = 0;
            }
            return false;
        }
        BlockPos below = c.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        if (++placeTicks > 14) {
            blockCooldown = 60;
            placeTicks = 0;
            return false;
        }
        Vec3 face = Vec3.atCenterOf(below).add(0.0, 0.5, 0.0);
        double fx = face.x - p.getX();
        double fy = face.y - p.getEyeY();
        double fz = face.z - p.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-fx, fz));
        float pitch = (float) -Math.toDegrees(Math.atan2(fy, Math.hypot(fx, fz)));
        bb.set(AIKeys.YAW, yaw);
        bb.set(AIKeys.PITCH, pitch);
        if (Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 20.0f && Math.abs(pitch - p.getXRot()) < 20.0f) {
            Placer.placeInto(bb, c);
        }
        return true;
    }

    // ------------------------------------------------------------------ main loop

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

    private void attack(Minecraft mc, LocalPlayer p) {
        mc.gameMode.attack(p, target);
        p.swing(InteractionHand.MAIN_HAND);
        sinceAttack = 0;
    }

    private void fight(Blackboard bb, LocalPlayer p, ClientLevel level, Minecraft mc) {
        sinceAttack++;
        if (swapCooldown > 0) {
            swapCooldown--;
        }
        if (blockCooldown > 0) {
            blockCooldown--;
        }
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
        String id = idOf(target);
        boolean creeper = id.equals("creeper");
        boolean low = p.getHealth() < 10.0f;
        boolean los = sees(level, p, target);

        manageShield(mc, p, (RANGED.contains(id) && los && reach > 3.5) || (creeper && swelling(target)));

        if (creeper && reach < 6.0 && reach > 1.8 && los && blockCooldown == 0 && !Modes.creative() && Placer.hasBlocks(p)
                && wallFront(bb, p, level, dx, dz, hd)) {
            return;
        }

        bb.set(AIKeys.YAW, yaw);
        bb.set(AIKeys.PITCH, pitch);
        equipWeapon(p);

        boolean airborne = !p.onGround() && !p.isInWater() && !p.isInLava();
        boolean falling = airborne && p.getDeltaMovement().y < -0.03 && p.fallDistance > 0.0;
        boolean melee = reach <= REACH - 0.4;
        int actions = 0;
        if (hd > 0.01) {
            InputOverride.setDirection(dx / hd, dz / hd);
        }
        if (!melee || !los) {
            actions |= InputOverride.FORWARD;
            if (reach > 4.5 && !jumped) {
                actions |= InputOverride.SPRINT;
            }
            if (p.horizontalCollision && p.onGround()) {
                actions |= InputOverride.JUMP;
            }
        } else if (creeper && reach < 2.2) {
            actions |= InputOverride.BACK;
        }

        boolean aimed = Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 9.0f && Math.abs(pitch - p.getXRot()) < 11.0f;
        boolean inReach = reach <= REACH && los && aimed && mc.gameMode != null;
        boolean ready = sinceAttack >= swingGap(p) && p.getAttackStrengthScale(0.0f) >= 0.9f;
        if (p.onGround() && !inReach) {
            jumped = false;
        }
        if (inReach && ready) {
            if (low) {
                actions |= InputOverride.SPRINT | InputOverride.FORWARD;
                attack(mc, p);
                wtap = 3;
            } else if (p.onGround() && !jumped) {
                actions |= InputOverride.JUMP;
                InputOverride.forceJump();
                jumped = true;
                critWait = 0;
            } else if (falling) {
                attack(mc, p);
                jumped = false;
            } else if (p.onGround() && jumped && ++critWait > 8) {
                attack(mc, p);
                jumped = false;
            }
        }
        if (wtap > 0) {
            wtap--;
            if (wtap >= 1) {
                actions &= ~(InputOverride.FORWARD | InputOverride.SPRINT);
            }
        }
        if (jumped) {
            actions &= ~InputOverride.SPRINT;
        }
        AIKeys.addActions(bb, actions);
    }
}