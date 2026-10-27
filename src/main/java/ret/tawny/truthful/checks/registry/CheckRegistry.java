package ret.tawny.truthful.checks.registry;

import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.impl.bedrock.BFlyA;
import ret.tawny.truthful.checks.impl.bedrock.BReachA;
import ret.tawny.truthful.checks.impl.bedrock.BSpeedA;
import ret.tawny.truthful.checks.impl.combat.aim.*;
import ret.tawny.truthful.checks.impl.combat.anchor.AnchorAuraA;
import ret.tawny.truthful.checks.impl.combat.autoclicker.*;
import ret.tawny.truthful.checks.impl.combat.crystal.CrystalAuraA;
import ret.tawny.truthful.checks.impl.combat.hitbox.HitboxA;
import ret.tawny.truthful.checks.impl.combat.killaura.*;
import ret.tawny.truthful.checks.impl.combat.lag.LagA;
import ret.tawny.truthful.checks.impl.combat.lag.LagB;
import ret.tawny.truthful.checks.impl.combat.lag.LagC;
import ret.tawny.truthful.checks.impl.combat.reach.ReachA;
import ret.tawny.truthful.checks.impl.movement.baritone.BaritoneA;
import ret.tawny.truthful.checks.impl.movement.baritone.BaritoneB;
import ret.tawny.truthful.checks.impl.movement.baritone.BaritoneC;
import ret.tawny.truthful.checks.impl.movement.inventory.InventoryA;
import ret.tawny.truthful.checks.impl.movement.simulation.Simulation;
import ret.tawny.truthful.checks.impl.movement.spoof.*;
import ret.tawny.truthful.checks.impl.movement.velocity.*;
import ret.tawny.truthful.checks.impl.packet.badpacket.*;
import ret.tawny.truthful.checks.impl.packet.crasher.CrasherA;
import ret.tawny.truthful.checks.impl.packet.invalid.InvalidA;
import ret.tawny.truthful.checks.impl.packet.order.*;
import ret.tawny.truthful.checks.impl.packet.sprint.SprintA;
import ret.tawny.truthful.checks.impl.packet.sprint.SprintB;
import ret.tawny.truthful.checks.impl.packet.timer.TimerA;
import ret.tawny.truthful.checks.impl.raycast.RaycastA;
import ret.tawny.truthful.checks.impl.world.fastbreak.FastBreakA;
import ret.tawny.truthful.checks.impl.world.phase.PhaseA;
import ret.tawny.truthful.checks.impl.world.scaffold.ScaffoldFace;
import ret.tawny.truthful.checks.impl.world.scaffold.ScaffoldMotion;
import ret.tawny.truthful.checks.impl.world.scaffold.ScaffoldReach;
import ret.tawny.truthful.checks.impl.world.scaffold.ScaffoldRotation;
import ret.tawny.truthful.utils.reflection.Manager;

import java.util.ArrayList;
import java.util.List;

public final class CheckRegistry extends Manager<Class<? extends Check>, Check> {

    private final List<Check> movementChecks = new ArrayList<>();
    private final List<Check> packetChecks = new ArrayList<>();
    private final List<Check> sendPacketChecks = new ArrayList<>();
    private final List<Check> attackChecks = new ArrayList<>();
    private final List<Check> blockBreakChecks = new ArrayList<>();
    private final List<Check> vehicleMoveChecks = new ArrayList<>();
    private final List<Check> quitChecks = new ArrayList<>();

    public CheckRegistry() {
        // Aim Checks - intentionally minimal (3 basic GCD variants only).
        // Free plugin: no entropy/jerk/ML heuristics that hand cheat devs a spec.
        register(AimA.class, new AimA());
        register(AimB.class, new AimB());
        register(AimC.class, new AimC());

        // AutoClicker Checks
        register(AutoClickerA.class, new AutoClickerA());
        register(AutoClickerB.class, new AutoClickerB());
        register(AutoClickerC.class, new AutoClickerC());
        register(AutoClickerD.class, new AutoClickerD());
        register(AutoClickerE.class, new AutoClickerE());

        // Lag & Combat
        register(LagA.class, new LagA());
        register(LagB.class, new LagB());
        register(LagC.class, new LagC());
        register(HitboxA.class, new HitboxA());
        register(ReachA.class, new ReachA());

        // KillAura Checks
        register(KillAuraB.class, new KillAuraB());
        register(KillAuraC.class, new KillAuraC());
        register(KillAuraD.class, new KillAuraD());
        register(KillAuraE.class, new KillAuraE());
        register(KillAuraF.class, new KillAuraF());
        register(KillAuraG.class, new KillAuraG());
        register(KillAuraH.class, new KillAuraH());

        // Baritone Checks
        register(BaritoneA.class, new BaritoneA());
        register(BaritoneB.class, new BaritoneB());
        register(BaritoneC.class, new BaritoneC());

        // Simulation: one engine, one verdict — replaces the old A-E and Vector A-C suite.
        register(Simulation.class, new Simulation());

        // Velocity Checks
        register(VelocityA.class, new VelocityA());
        register(VelocityB.class, new VelocityB());
        register(VelocityC.class, new VelocityC());
        register(VelocityD.class, new VelocityD());

        // Inventory
        register(InventoryA.class, new InventoryA());

        // Ground Spoof Checks
        register(GroundSpoofB.class, new GroundSpoofB());
        register(GroundSpoofC.class, new GroundSpoofC());
        register(GroundSpoofD.class, new GroundSpoofD());
        register(GroundSpoofE.class, new GroundSpoofE());
        register(GroundSpoofF.class, new GroundSpoofF());
        register(GroundSpoofG.class, new GroundSpoofG());

        // Timer Check
        register(TimerA.class, new TimerA());

        // World & FastBreak
        register(FastBreakA.class, new FastBreakA());
        register(PhaseA.class, new PhaseA());

        // Scaffold: four focused checks replacing the old A-H suite - each is
        // physics/geometry-anchored (line-of-sight, reach, tower metronome,
        // occupied-cell placement) instead of statistical pattern guessing.
        register(ScaffoldRotation.class, new ScaffoldRotation());
        register(ScaffoldReach.class, new ScaffoldReach());
        register(ScaffoldMotion.class, new ScaffoldMotion());
        register(ScaffoldFace.class, new ScaffoldFace());

        // Raycast
        register(RaycastA.class, new RaycastA());

        // Packet Integrity
        register(CrasherA.class, new CrasherA());
        register(InvalidA.class, new InvalidA());

        // BadPackets, the whole lot. Numbers first, then letters.
        // Removed dupe/dead slots: 9 & X (=worse PacketOrderD/E), C (=InvalidA),
        // W (=weaker PacketOrderE). I is back with a new purpose: the
        // impossible-position guard (far-packet disabler).
        register(BadPacket5.class, new BadPacket5());
        register(BadPacket6.class, new BadPacket6());
        register(BadPacket8.class, new BadPacket8());
        register(BadPacketA.class, new BadPacketA());
        register(BadPacketB.class, new BadPacketB());
        register(BadPacketD.class, new BadPacketD());
        register(BadPacketE.class, new BadPacketE());
        register(BadPacketF.class, new BadPacketF());
        register(BadPacketG.class, new BadPacketG());
        register(BadPacketH.class, new BadPacketH());
        register(BadPacketI.class, new BadPacketI());
        register(BadPacketJ.class, new BadPacketJ());
        register(BadPacketK.class, new BadPacketK());
        register(BadPacketL.class, new BadPacketL());
        register(BadPacketM.class, new BadPacketM());
        register(BadPacketN.class, new BadPacketN());
        register(BadPacketO.class, new BadPacketO());
        register(BadPacketP.class, new BadPacketP());
        register(BadPacketQ.class, new BadPacketQ());
        register(BadPacketR.class, new BadPacketR());
        register(BadPacketS.class, new BadPacketS());
        register(BadPacketT.class, new BadPacketT());
        register(BadPacketU.class, new BadPacketU());
        register(BadPacketV.class, new BadPacketV());
        register(BadPacketY.class, new BadPacketY());
        register(BadPacketZ.class, new BadPacketZ());

        // Packet Order
        register(PacketOrderA.class, new PacketOrderA());
        register(PacketOrderB.class, new PacketOrderB());
        register(PacketOrderC.class, new PacketOrderC());
        register(PacketOrderD.class, new PacketOrderD());
        register(PacketOrderE.class, new PacketOrderE());

        // Sprint
        register(SprintA.class, new SprintA());
        register(SprintB.class, new SprintB());

        // Aura
        register(CrystalAuraA.class, new CrystalAuraA());
        register(AnchorAuraA.class, new AnchorAuraA());

        // Bedrock Platform
        register(BSpeedA.class, new BSpeedA());
        register(BFlyA.class, new BFlyA());
        register(BReachA.class, new BReachA());

        categorize();
        Truthful.getInstance().getConfiguration().cleanupOrphanedChecks(this.getCollection());
    }

    private void categorize() {
        movementChecks.clear();
        packetChecks.clear();
        sendPacketChecks.clear();
        attackChecks.clear();
        blockBreakChecks.clear();
        vehicleMoveChecks.clear();
        quitChecks.clear();

        for (Check check : getCollection()) {
            Class<?> clazz = check.getClass();
            try {
                if (clazz.getMethod("handleRelMove", ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper.class).getDeclaringClass() != Check.class) {
                    movementChecks.add(check);
                }
                if (clazz.getMethod("handlePacketPlayerReceive", com.github.retrooper.packetevents.event.PacketReceiveEvent.class).getDeclaringClass() != Check.class) {
                    packetChecks.add(check);
                }
                if (clazz.getMethod("handlePacketPlaySend", com.github.retrooper.packetevents.event.PacketSendEvent.class).getDeclaringClass() != Check.class) {
                    sendPacketChecks.add(check);
                }
                if (clazz.getMethod("onAttack", org.bukkit.event.entity.EntityDamageByEntityEvent.class).getDeclaringClass() != Check.class) {
                    attackChecks.add(check);
                }
                if (clazz.getMethod("onBlockBreak", org.bukkit.event.block.BlockBreakEvent.class).getDeclaringClass() != Check.class) {
                    blockBreakChecks.add(check);
                }
                if (clazz.getMethod("onVehicleMove", org.bukkit.event.vehicle.VehicleMoveEvent.class).getDeclaringClass() != Check.class) {
                    vehicleMoveChecks.add(check);
                }
                if (clazz.getMethod("onQuit", org.bukkit.event.player.PlayerQuitEvent.class).getDeclaringClass() != Check.class) {
                    quitChecks.add(check);
                }
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            }
        }
    }

    public void init() {
        this.getCollection().forEach(Check::init);
        long enabledChecks = this.getCollection().stream().filter(Check::isEnabled).count();
        Truthful.getInstance().getPlugin().getLogger().info("Successfully loaded and registered " + enabledChecks + " checks.");
        recomputePipelineRequirements();
    }

    // =====================================================================
    // PIPELINE REQUIREMENTS (derived from the enabled-check set)
    // =====================================================================
    // The packet pipeline keeps a per-player world model (chunk decode, block
    // tracking, ground probes, environment scans) and a compensated entity
    // tracker running at all times - expensive even when nothing reads them.
    // These aggregates are derived from which checks are ENABLED, so a
    // profile running only click checks pays nothing for world/entity state.
    // Rebuilt on init and on every GUI toggle (see Check.setEnabled).
    // Click timestamps, transactions, teleport bookkeeping and digging state
    // are NOT gated: those keep packet ordering and gameplay exemptions
    // correct regardless of which checks read them.

    private volatile boolean worldConsumed = true;
    private volatile boolean entityTrackingConsumed = true;

    public void recomputePipelineRequirements() {
        boolean world = false;
        boolean entities = false;
        for (Check check : getCollection()) {
            if (!check.isEnabled()) continue;
            if (check.requiresWorld()) world = true;
            if (check.requiresEntityTracking()) entities = true;
            if (world && entities) break;
        }
        this.worldConsumed = world;
        this.entityTrackingConsumed = entities;
    }

    /** True when at least one enabled check reads compensated block data. */
    public boolean isWorldConsumed() { return worldConsumed; }

    /** True when at least one enabled check reads the compensated entity tracker. */
    public boolean isEntityTrackingConsumed() { return entityTrackingConsumed; }

    /** True when the environment/entity scan task has any consumer at all. */
    public boolean isEnvironmentScanConsumed() { return worldConsumed || entityTrackingConsumed; }

    public List<Check> getMovementChecks() { return movementChecks; }
    public List<Check> getPacketChecks() { return packetChecks; }
    public List<Check> getSendPacketChecks() { return sendPacketChecks; }
    public List<Check> getAttackChecks() { return attackChecks; }
    public List<Check> getBlockBreakChecks() { return blockBreakChecks; }
    public List<Check> getVehicleMoveChecks() { return vehicleMoveChecks; }
    public List<Check> getQuitChecks() { return quitChecks; }

    public void resetAllViolations() {
        this.getCollection().forEach(Check::clearViolations);
    }
}