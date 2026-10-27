package ret.tawny.truthful.prediction;

import ret.tawny.truthful.data.PlayerData;

import java.util.ArrayList;
import java.util.List;

public final class UncertaintyHandler {

    private final List<UncertaintySource> sources = new ArrayList<>();

    public UncertaintyHandler() {}

    public void clear() {
        sources.clear();
    }

    public void add(double value, String reason) {
        if (value > 0) {
            sources.add(new UncertaintySource(value, reason));
        }
    }

    /**
     * Idempotent by contract: clears any previously recorded sources before
     * evaluating, so reuse on the same handler can never double-count.
     */
    public void evaluate(PlayerData data) {
        sources.clear();
        if (data.getDeltaXZ() < 0.035) {
            add(0.03, "min_movement");
        }

        if (data.isInWeb()) {
            add(0.1, "web_movement");
        }

        if (data.isInLiquid()) {
            add(0.06, "liquid_movement");
        }

        if (data.isOnClimbable()) {
            add(0.08, "climbable_movement");
        }

        if (data.isUnderBlock()) {
            add(0.04, "head_collision");
        }

        int ticksSincePlace = data.getTicksTracked() - data.getLastBlockPlaceTick();
        if (ticksSincePlace >= 0 && ticksSincePlace <= 3) {
            add(0.05, "block_placement");
        }

        int ticksSinceVelocity = data.getTicksTracked() - data.getLastVelocityTick();
        if (ticksSinceVelocity >= 0 && ticksSinceVelocity <= 5) {
            add(0.12 * (5 - ticksSinceVelocity), "velocity_pending");
        }

        if (data.isTeleportTick()) {
            add(0.5, "teleport_grace");
        }

        int ticksSinceTeleport = data.getTicksSinceTeleport();
        if (ticksSinceTeleport >= 0 && ticksSinceTeleport <= 3) {
            add(0.1 * (3 - ticksSinceTeleport), "post_teleport");
        }

        if (data.getMovementContext().isSlimeBounce()) {
            add(0.08, "slime_bounce");
        }

        if (data.getTicksTracked() - data.getLastIceTick() < 8) {
            add(0.04, "ice_sliding");
        }

        if (data.isSprinting() && data.getAirTicks() > 0) {
            add(0.02, "air_sprint");
        }

        if (data.getBaritoneTrust() < 50) {
            add(0.05, "low_baritone_trust");
        }

        if (data.isServerGround() != data.isClientGround()) {
            add(0.15, "ground_desync");
        }

        if (data.getAirTicks() > 0 && data.getAirTicks() <= 3) {
            add(0.08, "jump_uncertainty");
        }

        if (data.isSprinting() && data.getDeltaY() > 0.0D && data.getAirTicks() <= 5) {
            add(0.04, "sprint_jump_momentum");
        }

        double tps = ret.tawny.truthful.Truthful.getInstance().getTps();
        if (tps < 20.0D) {
            add(0.02 * (20.0D - tps), "low_tps");
        }

        if (data.getTicksTracked() - data.getLastSoulSandTick() < 5) {
            add(0.03, "soul_friction");
        }

        if (data.isSprinting() && !data.isLastSprinting()) {
            add(0.12, "sprint_start");
        }

        if (data.getDeltaXZ() < 0.2) {
            add(0.03, "min_movement");
        }

        if (Math.abs(data.getDeltaYaw()) > 5.0F || Math.abs(data.getDeltaX() - data.getLastDeltaX()) > 0.05) {
            add(0.03, "input_jitter");
        }

        if (data.getAirTicks() == 0 && data.getPositionTracker().getLastAirTicks() > 3) {
            add(0.05, "landing_tick");
        }
    }

    public double reduceOffset(double offset) {
        double totalUncertainty = 0;
        for (UncertaintySource source : sources) {
            totalUncertainty += source.value;
        }
        return Math.max(0, offset - totalUncertainty);
    }

    public double getTotalUncertainty() {
        double total = 0;
        for (UncertaintySource source : sources) {
            total += source.value;
        }
        return total;
    }

    public String getDebugInfo() {
        if (sources.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (UncertaintySource source : sources) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(source.reason).append(":").append(String.format("%.4f", source.value));
        }
        return sb.toString();
    }

    private static class UncertaintySource {
        final double value;
        final String reason;

        UncertaintySource(double value, String reason) {
            this.value = value;
            this.reason = reason;
        }
    }
}