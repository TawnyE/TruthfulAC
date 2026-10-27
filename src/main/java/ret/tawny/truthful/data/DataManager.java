package ret.tawny.truthful.data;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe storage for PlayerData.
 */
public final class DataManager {

    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

    // MASSIVE PERFORMANCE FIX: Maintain a set of staff who want alerts.
    // This prevents O(N) loops through all players during every flag.
    private final Set<UUID> alertSubscribers = ConcurrentHashMap.newKeySet();

    public void enter(final Player player) {
        if (player == null) return;
        var config = ret.tawny.truthful.Truthful.getInstance().getConfiguration();
        players.put(player.getUniqueId(), new PlayerData(player, config.getQueueMaxEntries(), config.getQueueTtlMillis()));

        // Auto-subscribe staff with permission on join if enabled in config
        if (player.hasPermission("truthful.alerts") && config.isAlertsAutoEnableOnJoin()) {
            alertSubscribers.add(player.getUniqueId());
        }
    }

    public void eliminate(final Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        PlayerData data = players.remove(uuid);
        alertSubscribers.remove(uuid);
        ret.tawny.truthful.Truthful.getInstance().getDiscordManager().removePlayer(uuid);

        if (data != null) {
            data.teardown();
            UUID worldUid = player.getWorld().getUID();
            ret.tawny.truthful.Truthful.getInstance().getServerScheduler().runGlobal(() -> {
                org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldUid);
                if (world != null && world.getPlayers().isEmpty()) {
                    ret.tawny.truthful.Truthful.getInstance().getGlobalWorldCache().clearWorld(worldUid);
                    ret.tawny.truthful.utils.world.BlockWriteDedup.removeWorld(worldUid);
                }
            });
        }
    }

    public PlayerData getPlayerData(final Player player) {
        if (player == null) return null;
        return players.get(player.getUniqueId());
    }

    public Collection<PlayerData> getCollection() {
        return players.values();
    }

    public Set<UUID> getAlertSubscribers() {
        return alertSubscribers;
    }

    public void teardownAll() {
        players.values().forEach(PlayerData::teardown);
        players.clear();
        alertSubscribers.clear();
    }

    public PlayerData getHighestPing() {
        long highestPing = 0L;
        PlayerData laggiest = null;

        for (final PlayerData playerData : this.getCollection()) {
            if (playerData == null) continue;
            final long ping = playerData.getPing();
            if (ping > highestPing) {
                highestPing = ping;
                laggiest = playerData;
            }
        }
        return laggiest;
    }
}