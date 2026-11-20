package dev.nullclient.ai.task;

import dev.nullclient.ai.AIKeys;
import dev.nullclient.ai.RunLog;
import dev.nullclient.ai.bt.Blackboard;
import dev.nullclient.ai.plan.Inv;
import dev.nullclient.ai.world.Vision;
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
 * Fights mobs only, like a player would, with normal vanilla reach and cooldowns, and without spamming:
 * <ul>
 * <li>the wait between swings comes from the weapon's attack speed, plus a full attack cooldown, a clear line, the aim
 * on the mob, and the mob's hit flash over;</li>
 * <li>after 3 swings that did no damage it backs off for 1.5 seconds instead of clicking on;</li>
 * <li>crits (toggle): it starts a jump just before the swing is ready and hits on the way down, without sprinting;</li>
 * <li>w-tap (toggle): in sprint-hit mode it releases forward for 3 ticks after each hit;</li>
 * <li>shield (toggle): offhand shield raised against ranged mobs and fusing creepers;</li>
 * <li>creeper wall (toggle): one block placed between you and an approaching creeper, at most every 3 ticks.</li>
 * </ul>
 * Never targets players. With Vision on it only fights mobs it has seen. No automatic hitting, no extended reach.
 */
public final class Combat {
    public static volatile boolean enabled = true;
    public static volatile boolean crits = true;
    public static volatile boolean wtap = true;
    public static volatile boolean shield = true;
    public static volatile boolean creeperWall = true;

    private static volatile String state = "";

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
    private int wtapLeft;
    private int critWait;
    private boolean jumped;
    private boolean shieldHeld;
    private int swapCooldown;
    private int blockCooldown;
    private int placeTicks;
    private int placeGap;
    private int pause;
    private int misses;
    private float lastHp = -1.0f;
    private int lastTargetId = -1;

    /** What the fight looks like right now, for the AI window (empty when not fighting). */
    public static String state() {
        return state;
    }

    public boolean active() {
        return target != null;
    }

    public void clear() {
        target = null;
        engaged = 0;
        jumped = false;
        wtapLeft = 0;
        pause = 0;
        misses = 0;
        lastHp = -1.0f;
        state = "";
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

    /** A mob that just hurt us, or a hostile mob close by that we know about and have a clear line to. */
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
            if (LEAVE_ALONE.contains(idOf(e)) || !Vision.knowsMob(e)) {
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

    /** Ticks between swings for the item in hand: a full charge takes 20 / attack-speed ticks. */
    private static int swingGap(LocalPlayer p) {
        Inventory inv = p.getInventory();
        String id = Inv.idOf(inv.getItem(inv.getSelectedSlot()));
        double speed;
        if (id.endsWith("_sword")) {
            speed = 1.6;
        } else if (id.equals("mace")) {
            speed = 0.6;
        } else if (id.equals("trident")) {
            speed = 1.1;
        } else if (id.endsWith("_axe")) {
            speed = id.startsWith("wooden") || id.startsWith("stone") ? 0.8 : id.startsWith("iron") ? 0.9 : 1.0;
        } else if (id.endsWith("_pickaxe")) {
            speed = 1.2;
        } else if (id.endsWith("_shovel")) {
            speed = 1.0;
        } else if (id.endsWith("_hoe")) {
            speed = id.startsWith("stone") ? 2.0 : id.startsWith("iron") ? 3.0 : id.startsWith("diamond") || id.startsWith("netherite") ? 4.0 : 1.0;
        } else {
            speed = 4.0;
        }
        return (int) Math.ceil(20.0 / speed) + 1;
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

    /** Puts a block on the cell between us and the creeper, one placement every 3 ticks. Returns true while busy. */
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
        if (placeGap > 0) {
            placeGap--;
        } else if (Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 20.0f && Math.abs(pitch - p.getXRot()) < 20.0f) {
            Placer.placeInto(bb, c);
            placeGap = 3;
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

    /** One swing, unless the last swings did no damage. Returns true if the swing was made. */
    private boolean strike(Minecraft mc, LocalPlayer p) {
        float hp = target.getHealth();
        if (lastTargetId == target.getId() && lastHp >= 0.0f && hp >= lastHp - 0.01f) {
            if (++misses >= 3) {
                misses = 0;
                pause = 30;
                lastHp = -1.0f;
                RunLog.add("my hits are not damaging " + idOf(target) + " - backing off for a moment");
                return false;
            }
        } else {
            misses = 0;
        }
        lastTargetId = target.getId();
        lastHp = hp;
        mc.gameMode.attack(p, target);
        p.swing(InteractionHand.MAIN_HAND);
        sinceAttack = 0;
        return true;
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
        boolean sprintHit = low || !crits;
        state = id + " " + Math.round(reach) + "m, its hp " + Math.round(target.getHealth())
                + (pause > 0 ? " - backing off" : sprintHit ? " - sprint hits" : " - crit hits");

        manageShield(mc, p, shield && ((RANGED.contains(id) && los && reach > 3.5) || (creeper && swelling(target))));

        if (creeperWall && creeper && reach < 6.0 && reach > 1.8 && los && blockCooldown == 0 && !Modes.creative()
                && Placer.hasBlocks(p) && wallFront(bb, p, level, dx, dz, hd)) {
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
        int gap = swingGap(p);
        boolean ready = sinceAttack >= gap && p.getAttackStrengthScale(0.0f) >= 0.95f && target.hurtTime <= 1;
        if (p.onGround() && !inReach) {
            jumped = false;
        }

        if (pause > 0) {
            pause--;
            actions &= ~(InputOverride.FORWARD | InputOverride.SPRINT);
            actions |= InputOverride.BACK;
        } else if (inReach) {
            if (sprintHit) {
                if (ready) {
                    actions |= InputOverride.FORWARD | InputOverride.SPRINT;
                    if (strike(mc, p) && wtap) {
                        wtapLeft = 3;
                    }
                } else if (melee) {
                    actions |= InputOverride.FORWARD | InputOverride.SPRINT;
                }
            } else {
                boolean nearReady = sinceAttack >= gap - 9;
                if (p.onGround() && !jumped && nearReady) {
                    actions |= InputOverride.JUMP;
                    InputOverride.forceJump();
                    jumped = true;
                    critWait = 0;
                } else if (ready && falling) {
                    strike(mc, p);
                    jumped = false;
                } else if (jumped && p.onGround() && ++critWait > 6 && ready) {
                    strike(mc, p);
                    jumped = false;
                }
            }
        }
        if (wtapLeft > 0) {
            wtapLeft--;
            actions &= ~(InputOverride.FORWARD | InputOverride.SPRINT);
        }
        if (jumped) {
            actions &= ~InputOverride.SPRINT;
        }
        AIKeys.addActions(bb, actions);
    }
}