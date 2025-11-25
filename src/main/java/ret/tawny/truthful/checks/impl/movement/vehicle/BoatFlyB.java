package ret.tawny.truthful.checks.impl.movement.vehicle;

import org.bukkit.Material;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

@CheckData(order = 'B', type = CheckType.VEHICLE)
public final class BoatFlyB extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @EventHandler
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!isEnabled()) return;

        Vehicle vehicle = event.getVehicle();
        if (!(vehicle instanceof Boat)) return;
        if (vehicle.getPassengers().isEmpty()) return;

        if (!(vehicle.getPassengers().get(0) instanceof Player)) return;
        Player player = (Player) vehicle.getPassengers().get(0);

        if (Truthful.getInstance().isBedrockPlayer(player)) return;

        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        double deltaX = event.getTo().getX() - event.getFrom().getX();
        double deltaZ = event.getTo().getZ() - event.getFrom().getZ();
        double speed = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        // Standard land speed is extremely low (~0.06). Ice is fast.
        double maxSpeed = 0.6; // Generous cap for land

        if (isIceBelow(vehicle)) maxSpeed = 1.2; // Blue ice is fast
        if (isInWater(vehicle)) return; // Vanilla water physics handles limit well usually, or distinct check

        if (speed > maxSpeed && !vehicle.getLocation().getBlock().isLiquid()) {
            if (buffer.increase(player, 1.0) > 10.0) {
                flag(data, String.format("Boat Speed. Speed: %.3f, Max: %.3f", speed, maxSpeed));
                vehicle.eject();
                buffer.reset(player, 5.0);
            }
        } else {
            buffer.decrease(player, 0.1);
        }
    }

    private boolean isIceBelow(Vehicle v) {
        Material m = v.getLocation().subtract(0, 1, 0).getBlock().getType();
        return m.name().contains("ICE");
    }

    private boolean isInWater(Vehicle v) {
        Material m = v.getLocation().getBlock().getType();
        return m.name().contains("WATER");
    }
}