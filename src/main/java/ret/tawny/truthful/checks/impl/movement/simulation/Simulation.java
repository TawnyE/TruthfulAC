package ret.tawny.truthful.checks.impl.movement.simulation;

import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.checks.impl.movement.MovementCheckSupport;
import ret.tawny.truthful.data.ExemptionType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.predictionengine.PredictionEngine;
import ret.tawny.truthful.predictionengine.PredictionResult;
import ret.tawny.truthful.utils.world.PhysicsConstants;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@CheckData(order = 'A', type = CheckType.SIMULATION, displayName = "Simulation")
public final class Simulation extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0D);

    private static final class State {
        int violTicks;
        double leak;
        int hoverTicks;
        // Jump takeoff precision: vanilla jumps are exact (0.42 + 0.1*boost,
        // float). Consistently exceeding it is a jump-strength cheat; head
        // bumps truncate jumps legitimately and are exempt.
        double takeoffExcess;
        int takeoffStreak;
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isPositionUpdate()) return;
        final PlayerData data = wrapper.getPlayerData();
        if (data == null) return;

        if (data.isServerFrozen() || data.isTeleportTick() || data.isTeleportPending()
                || data.isExempt(ExemptionType.JOIN)
                || data.isAllowFlight() || data.isFlying()
                || data.isExempt(ExemptionType.MACE_SMASH)
                || data.getTicksTracked() - data.getLastVehicleExitTick() < 10) {
            // NOTE: vehicles are NOT skipped here - PredictionEngine routes
            // them to the vehicle physics model (boat fly is a real target).
            // NOTE: spear lunges are NOT skipped either - the engine now
            // simulates the dash as a bounded impulse (see PredictionState).
            decay(data, 0.15);
            return;
        }

        if (!data.isChunkLoaded()) {
            // Uncached chunk = never judge (empty world views fabricate
            // violations) - but ONLY for claims reachable from the last
            // accepted position. A ghost far-position claim must be judged:
            // silently decaying it here was the last hiding spot of the
            // far-packet disabler's skip chain.
            if (data.getDeltaXZ() <= ret.tawny.truthful.utils.math.PositionSanity.MAX_JUMP
                    && Math.abs(data.getDeltaY()) <= ret.tawny.truthful.utils.math.PositionSanity.MAX_JUMP) {
                decay(data, 0.20);
                return;
            }
        }

        final UUID id = data.getPlayer().getUniqueId();
        final State st = states.computeIfAbsent(id, k -> new State());

        final double accX = data.getDeltaX(), accY = data.getDeltaY(), accZ = data.getDeltaZ();
        final double lastY = data.getLastDeltaY();

        if (accX == 0 && accZ == 0 && Math.abs(accY) < 0.003D
                && zeroMinMotion(data.getLastDeltaX()) == 0
                && zeroMinMotion(data.getLastDeltaZ()) == 0
                && Math.abs(lastY) < 0.003D) {
            decay(data, 0.05);
            return;
        }

        final boolean onGround = data.isServerGround();
        final boolean wasOnGround = data.isLastGround();
        final int airTicks = data.getAirTicks();

        PredictionResult r = PredictionEngine.simulate(data);

        // Uncertainty discount (recent damage, collisions, landing ticks) â€”
        // Residual jitter only. The engine already subtracts its own named
        // per-cause allowances, so applying the full uncertaintySum here was
        // double lenience that systematically excused real movement. Keep a
        // quarter (capped) as a transition buffer.
        double uncertainty = Math.min(0.05D,
                MovementCheckSupport.uncertaintySum(data) * 0.25D);

        boolean vehicle = data.isInsideVehicle();
        // Known-input ticks (1.21.2+ PLAYER_INPUT era) run exact candidates:
        // only float slack remains, so the margins shrink toward Grim-style
        // 0.001 territory. Brute-input ticks keep the coverage-gap margin.
        double xMargin = vehicle ? 0.10D : (r.knownInput ? 0.003D : 0.008D);
        double yMargin = data.isInLiquid() || data.isGliding() ? 0.04D : (r.knownInput ? 0.003D : 0.008D);

        double scoredXZ = Math.max(0.0D, r.offsetXZ - uncertainty) - xMargin;
        double scoredY = Math.max(0.0D, r.offsetY - uncertainty * 0.5D) - yMargin;

        // Velocity gating for the hard caps: only a velocity the client has
        // ACKNOWLEDGED (transaction-bracketed) in the last few ticks justifies
        // exceeding a cap. hasActiveVelocity() counts pending/unacked entries
        // too, which a "withhold the ack" cheat could farm for 5 seconds.
        boolean hasRecentAckedVelocity = data.getVelocities().hasAcknowledgedVelocity(10, true);

        // Absolute air-speed ceiling - never during glide transitions (the
        // gliding cache lags the pose by several packets; window matches
        // the dual-regime glide window).
        if (!vehicle && !data.isInLiquid() && !data.isGliding() && !onGround
                && airTicks >= 4 && !hasRecentAckedVelocity
                && data.getTicksTracked() - data.getLastGlideTick() > 40) {
            double airCap = MovementCheckSupport.computeMaxHorizontalSpeed(data) + 0.05D;
            double deltaXZ = data.getDeltaXZ();
            if (deltaXZ > airCap) {
                scoredXZ = Math.max(scoredXZ, deltaXZ - airCap);
                r.hardViolation = true;
                r.best = "air_speed_cap";
            }
        }

        // NoSlow enforcement: when a slowdown is active, the multiplied cap is
        // the legal ceiling on ALL frames. Only enforced when the ENGINE
        // FAILED to explain the tick - when candidates already match (edge
        // of a web, transition tick), the engine's verdict is authoritative.
        if (!vehicle && !data.isInLiquid() && r != null && r.slowMult < 0.99D && r.offsetXZ > 0.02D) {
            double legalMax = MovementCheckSupport.computeMaxHorizontalSpeed(data) * r.slowMult + 0.03D;
            double deltaXZ = data.getDeltaXZ();
            if (deltaXZ > legalMax && !hasRecentAckedVelocity) {
                scoredXZ = Math.max(scoredXZ, deltaXZ - legalMax);
                r.hardViolation = true;
                r.best = "noslow";
            }
        }

        // --- Ground enforcement (vanilla hard laws) ---
        final double deltaXZ = data.getDeltaXZ();
        final double lastXZ = data.getLastDeltaXZ();
        if (!vehicle && !data.isInLiquid() && !data.isGliding() && onGround && !hasRecentAckedVelocity) {
            // 1. Sprint speed ceiling on GROUND ticks (jump tick gets the
            //    sprint-jump allowance, landing ticks decay).
            double groundCap = MovementCheckSupport.computeMaxHorizontalSpeed(data)
                    + 0.03D;                       // exponential-fudge margin
            if (data.isSprinting() && wasOnGround && airTicks == 0) {
                groundCap += 0.20D;              // sprint-jump carry-over
            }
            if (deltaXZ > groundCap && lastXZ > deltaXZ * 0.5D) {
                scoredXZ = Math.max(scoredXZ, deltaXZ - groundCap);
                r.hardViolation = true;
                r.best = "ground_speed_cap";
            }

            // 2. Post-attack momentum law: Landed attacks multiply horizontal
            //    momentum by 0.6 that tick. Only enforced when the ENGINE
            //    failed to explain the tick - when candidates already match
            //    (strafing A/D redirects momentum legally), the engine's
            //    verdict is authoritative and this law must not override it.
            int ticksSinceAttack = data.getTicksTracked() - data.getLastAttackPacketTick();
            if (ticksSinceAttack <= 1 && lastXZ > 0.10D && deltaXZ > lastXZ * 0.75D + 0.03D
                    && r.offsetXZ > 0.02D) {
                scoredXZ = Math.max(scoredXZ, 0.06D);
                r.hardViolation = true;
                r.best = "post_attack_momentum";
            }

            // 3. Omni-directional sprint: engine verified backwards sprint.
            if (r.sprintBackwards) {
                // Not an instant hard flag: a single turning tick on a slick
                // surface can briefly oppose the look. Needs the sustained
                // buffer/leak path like other subtle signals.
                scoredXZ = Math.max(scoredXZ, 0.08D);
                r.best = "omni_sprint";
            }
        }

        // Gravity law / hover: in the on-foot regime (no liquid, no glide, no
        // climb, no web, no powder snow, no bubble column) vanilla CANNOT hold
        // deltaY near zero in air â€” the fall chain guarantees at least
        // -0.0784 per tick. Horizontal drift is irrelevant to the law, so any
        // air time with |deltaY| under the fall-chain noise floor is hover.
        boolean hoverContext = !vehicle && !data.isInLiquid() && !data.isGliding()
                && !data.isOnClimbable() && !data.isInWeb()
                && !data.getMovementContext().isPowderSnow()
                && data.getPositionTracker().getBubbleColumnDrag() == null;
        if (hoverContext && !onGround && Math.abs(accY) < 0.0045D && airTicks > 6) {
            st.hoverTicks++;
        } else {
            st.hoverTicks = 0;
        }

        // Jump takeoff precision: on the first airborne tick of a jump the
        // vertical motion is exactly jumpStrength (0.42 + 0.1*jumpBoost) in
        // vanilla floats. A consistent excess above float noise is a modified
        // jump height. Head-bump contact truncates jumps legitimately and
        // recent contact is exempt. Step-height rises (slabs, stairs) are
        // NOT modified jumps - vanilla steps up to stepHeight.
        if (airTicks == 1 && wasOnGround && accY > 0.2D
                && accY > Math.max(0.601D, data.getStepHeight() + 0.06D)) {
            double expected = data.getJumpStrength()
                    + data.getPotionLevel(org.bukkit.potion.PotionEffectType.JUMP_BOOST) * 0.1D;
            double excess = accY - expected;
            if (excess > 0.002D && !data.isUnderBlock()
                    && data.getTicksTracked() - data.getLastUnderBlockTick() > 3) {
                st.takeoffExcess += excess;
                st.takeoffStreak++;
                if (st.takeoffStreak >= 3) {
                    r.hardViolation = true;
                    r.best = "jump_strength";
                    r.offsetY = Math.max(r.offsetY, st.takeoffExcess);
                }
            } else {
                st.takeoffExcess = Math.max(0.0D, st.takeoffExcess - 0.01D);
                st.takeoffStreak = Math.max(0, st.takeoffStreak - 1);
            }
        }

        boolean violation = scoredY > 0.0D || scoredXZ > 0.0D || st.hoverTicks >= 4;

        if (violation) {
            st.violTicks++;
            st.leak += Math.max(scoredY, scoredXZ);
        } else {
            st.violTicks = Math.max(0, st.violTicks - 1);
            st.leak = Math.max(0.0D, st.leak - 0.04D);
            buffer.decrease(data.getPlayer(), 0.12);
        }

        double hardBound = Math.max(scoredY, scoredXZ);
        boolean hard = r.hardViolation || st.hoverTicks >= 6 || hardBound > 0.25D;

        // Single-tick semantics (Grim/Intave): a blatant physics violation on
        // ONE tick flags immediately - one mid-air jump with no knockback is a
        // ~0.42 vertical offset that no legal candidate explains. Only the
        // subtle sustained path still needs consecutive ticks + leak.
        boolean blatant = r.hardViolation && (hardBound > 0.05D || r.best.equals("air_jump"));
        if ((blatant && st.violTicks >= 1)
                || (hard && !r.hardViolation && st.violTicks >= 2)
                || (!hard && st.leak > 0.14D && st.violTicks >= 2)) {
            double amount = 1.0D + hardBound * 8.0D;
            if (buffer.increase(data.getPlayer(), amount) > 3.0D) {
        // Rich, greppable diagnostics. Search logs for "SIM fail=".
        // carry = velocity the engine will carry into the next tick;
        // pred  = resolved motion of the winning candidate; act = client delta.
        String env = ret.tawny.truthful.debug.DebugContext.blocks(data);
        String ctx = ret.tawny.truthful.debug.DebugContext.build(data);
        String ver = Truthful.getInstance().getVersionManager().getAdapter().getVersion().display();

        flag(data, String.format(
                "SIM fail=%s offXZ=%.4f offY=%.4f cands=%d ver=%s in=%s fr=%.4f raw=%.3f carry=[%.4f,%.4f,%.4f] pred=[%.4f,%.4f,%.4f] act=[%.4f,%.4f,%.4f] lastY=%.4f air=%d hover=%d og=%b pog=%b cg=%b pos=[%.2f,%.2f,%.2f] allow=[%s]%s %s",
                r.best, r.offsetXZ, r.offsetY, r.candidateCount, ver,
                r.knownInput ? "known" : "brute", r.friction, r.rawFriction,
                r.carriedX, r.carriedY, r.carriedZ, r.predX, r.predY, r.predZ,
                accX, accY, accZ, lastY, airTicks, st.hoverTicks, onGround, wasOnGround,
                data.isClientGround(), data.getX(), data.getY(), data.getZ(), r.allowanceDebug, env, ctx));
                debugStatus(data, String.format("off=%.3f best=%s cands=%d", r.offset, r.best, r.candidateCount),
                        buffer.get(data.getPlayer()), 4.0D);
                buffer.reset(data.getPlayer(), 1.5D);
                st.leak *= 0.5D;
                st.violTicks = Math.min(st.violTicks, 1);
                st.hoverTicks = Math.min(st.hoverTicks, 2);
            }
        }
    }

    private static double zeroMinMotion(double v) {
        return Math.abs(v) < PhysicsConstants.MIN_MOTION ? 0.0D : v;
    }

    private void decay(PlayerData data, double by) {
        buffer.decrease(data.getPlayer(), by);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
        buffer.remove(event.getPlayer());
    }
}
