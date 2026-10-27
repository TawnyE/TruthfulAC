package ret.tawny.truthful.debug;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ret.tawny.truthful.commands.impl.CommandManager;
import ret.tawny.truthful.data.PlayerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DebugManager {

    private final Map<UUID, String> debuggingChecks = new ConcurrentHashMap<>();

    public boolean isDebuggingCheck(Player player) {
        return debuggingChecks.containsKey(player.getUniqueId());
    }

    public String getDebuggingCheck(Player player) {
        return debuggingChecks.get(player.getUniqueId());
    }

    public void startDebuggingCheck(Player player, String checkName) {
        debuggingChecks.put(player.getUniqueId(), checkName);
    }

    public void stopDebuggingCheck(Player player) {
        debuggingChecks.remove(player.getUniqueId());
    }

    public void stopDebuggingCheck(UUID uuid) {
        debuggingChecks.remove(uuid);
    }

    /**
     * Broadcasts live real-time check status with 3-tier status indicator ([CLEAR], [NEAR_FLAG], [FLAGGED]).
     */
    public void sendDebugStatus(PlayerData data, String checkName, String details, double currentBuffer, double maxThreshold) {
        if (data == null || data.getPlayer() == null) return;
        Player target = data.getPlayer();
        DebugStatus status = DebugStatus.getFromBuffer(currentBuffer, maxThreshold);

        String actionBarText = String.format("%s §8[§b%s§8] §f%s §8| §7Buf: %s%.1f§7/%.1f §8| §7XZ: §f%.3f §7Y: §f%.3f",
                status.getBadge(), checkName, target.getName(), status.getColorCode(), currentBuffer, maxThreshold, data.getDeltaXZ(), data.getDeltaY());

        Component component = LegacyComponentSerializer.legacySection().deserialize(actionBarText);
        WrapperPlayServerActionBar packet = new WrapperPlayServerActionBar(component);

        String chatText = String.format("%s §8[§b%s§8] §f%s §8- %s §7(Buf: %s%.1f§7/%.1f)",
                status.getBadge(), checkName, target.getName(), details, status.getColorCode(), currentBuffer, maxThreshold);

        for (UUID uuid : CommandManager.debuggers) {
            Player debugger = Bukkit.getPlayer(uuid);
            if (debugger != null) {
                String focus = debuggingChecks.get(uuid);
                if (focus != null && (focus.equalsIgnoreCase("ALL") || focus.equalsIgnoreCase("ANY") || focus.toLowerCase().contains(checkName.toLowerCase()))) {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(debugger, packet);
                    if (status == DebugStatus.NEAR_FLAG || status == DebugStatus.FLAGGED) {
                        // Chat from packet threads is not thread-safe on Spigot/Folia.
                        ret.tawny.truthful.Truthful.getInstance().getServerScheduler()
                                .runRegion(debugger, () -> debugger.sendMessage(chatText));
                    }
                }
            }
        }
    }

    public void sendVerbose(String checkName, String info) {
        String text = "§8[§7" + checkName + "§8] §f" + info;
        Component component = LegacyComponentSerializer.legacySection().deserialize(text);
        WrapperPlayServerActionBar packet = new WrapperPlayServerActionBar(component);

        for (UUID uuid : CommandManager.debuggers) {
            Player debugger = Bukkit.getPlayer(uuid);
            if (debugger != null) {
                String focus = debuggingChecks.get(uuid);
                if (focus != null && (focus.equalsIgnoreCase("ALL") || focus.equalsIgnoreCase("ANY") || focus.toLowerCase().contains(checkName.toLowerCase()))) {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(debugger, packet);
                }
            }
        }
    }

    public void sendSuspicion(String checkName, String info, double buffer) {
        String message = "§8[§e" + checkName + "§8] §e" + info + " §7(Buf: " + String.format("%.1f", buffer) + ")";
        for (UUID uuid : CommandManager.debuggers) {
            Player debugger = Bukkit.getPlayer(uuid);
            if (debugger != null) {
                String focus = debuggingChecks.get(uuid);
                if (focus != null && (focus.equalsIgnoreCase("ALL") || focus.equalsIgnoreCase("ANY") || focus.toLowerCase().contains(checkName.toLowerCase()))) {
                    ret.tawny.truthful.Truthful.getInstance().getServerScheduler()
                            .runRegion(debugger, () -> debugger.sendMessage(message));
                }
            }
        }
    }
}