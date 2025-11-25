package ret.tawny.truthful.checks.impl.movement.vehicle;

import org.bukkit.Material;
import org.bukkit.block.Block;
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

@CheckData(order = 'A', type = CheckType.VEHICLE)
public final class BoatFlyA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @EventHandler
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!isEnabled()) return;

        Vehicle vehicle = event.getVehicle();
        if (!(vehicle instanceof Boat)) return;
        if (vehicle.getPassengers().isEmpty()) return;

        // Only check if a player is driving
        if (!(vehicle.getPassengers().get(0) instanceof Player)) return;
        Player player = (Player) vehicle.getPassengers().get(0);

        if (Truthful.getInstance().isBedrockPlayer(player)) return;

        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        double fromY = event.getFrom().getY();
        double toY = event.getTo().getY();
        double deltaY = toY - fromY;

        // Exemptions
        if (isInWater(vehicle)) return;
        if (isNearSlime(vehicle)) return;

        // Logic 1: Going UP (Boats cannot fly up without water)
        if (deltaY > 0.0) {
            // Bubbles?
            if (vehicle.getLocation().getBlock().getType().name().contains("BUBBLE")) return;

            if (buffer.increase(player, 1.0) > 10.0) {
                flag(data, String.format("Boat Ascension. Y: +%.4f", deltaY));
                vehicle.eject(); // Dismount them
                buffer.reset(player, 5.0);
            }
        }
        // Logic 2: Hover (Boats must fall)
        else if (deltaY == 0.0 && !vehicle.isOnGround()) {
            // Check if really in air
            Block below = vehicle.getLocation().subtract(0, 0.4, 0).getBlock();
            if (below.getType() == Material.AIR) {
                if (buffer.increase(player, 1.0) > 10.0) {
                    flag(data, "Boat Hover");
                    vehicle.eject();
                    buffer.reset(player, 5.0);
                }
            }
        } else {
            buffer.decrease(player, 0.2);
        }
    }

    private boolean isInWater(Vehicle v) {
        Material m = v.getLocation().getBlock().getType();
        return m.name().contains("WATER");
    }

    private boolean isNearSlime(Vehicle v) {
        // Simple check below
        return v.getLocation().subtract(0, 1, 0).getBlock().getType().name().contains("SLIME");
    }
}