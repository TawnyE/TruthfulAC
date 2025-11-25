package ret.tawny.truthful.checks.api;

import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public abstract class Check implements Listener {
    private final char order;
    private final CheckType checkType;
    private final String formattedName;
    private final boolean enabled;

    private static final Map<UUID, Long> teleportCooldowns = new ConcurrentHashMap<>();

    protected Check() {
        final CheckData checkData = this.getClass().getAnnotation(CheckData.class);
        this.order = checkData.order();
        this.checkType = checkData.type();
        this.formattedName = this.checkType.getName(this);
        this.enabled = Truthful.getInstance().getConfiguration().isCheckEnabled(this.checkType.name(), String.valueOf(this.order));

        if (this.enabled) {
            Bukkit.getPluginManager().registerEvents(this, Truthful.getInstance().getPlugin());
        }
    }

    protected void flag(final PlayerData player, final String debug) {
        if (player.isExempt()) return;

        int vl = player.increment();

        // Use Configurable Message
        String rawMessage = Truthful.getInstance().getConfiguration().getAlertMessage();
        String message = rawMessage
                .replace("%player%", player.getPlayer().getName())
                .replace("%check%", this.formattedName)
                .replace("%vl%", String.valueOf(vl))
                .replace("%ping%", String.valueOf(player.getPing()))
                .replace("%debug%", debug);

        Bukkit.getScheduler().runTask(Truthful.getInstance().getPlugin(), () -> {
            Player p = player.getPlayer();
            if (!p.isOnline()) return;

            Bukkit.getLogger().info(p.getName() + " failed " + this.formattedName + ": " + debug);

            for (final Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("truthful.alerts")) {
                    staff.sendMessage(message);
                }
            }

            if (Truthful.getInstance().getConfiguration().isLagbacks()) {
                if (checkType == CheckType.SPEED || checkType == CheckType.FLY ||
                        checkType == CheckType.JESUS || checkType == CheckType.ELYTRA ||
                        checkType == CheckType.VEHICLE) { // Added Vehicle lagback

                    if (p.isDead()) return;

                    long now = System.currentTimeMillis();
                    long lastTeleport = teleportCooldowns.getOrDefault(p.getUniqueId(), 0L);

                    if (now - lastTeleport > 200) {
                        p.teleport(player.getLastLocation());
                        teleportCooldowns.put(p.getUniqueId(), now);
                    }
                }
            }
        });

        Truthful.getInstance().getLogManager().log(
                player.getPlayer().getUniqueId(),
                player.getPlayer().getName(),
                this.formattedName,
                vl,
                player.getPing(),
                debug
        );
    }

    public final char getOrder() { return this.order; }
    public final String getFormattedName() { return this.formattedName; }
    public final boolean isEnabled() { return this.enabled; }

    public void onPacketPlaySend(PacketEvent event) {
        if (shouldCheck(event.getPlayer())) handlePacketPlaySend(event);
    }

    public void onPacketPlayerReceive(PacketEvent event) {
        if (shouldCheck(event.getPlayer())) handlePacketPlayerReceive(event);
    }

    public void onRelMove(RelMovePacketWrapper event) {
        if (shouldCheck(event.getPlayer())) handleRelMove(event);
    }

    private boolean shouldCheck(Player player) {
        if (!enabled || Truthful.getInstance().isBedrockPlayer(player)) return false;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        return data != null && !data.isExempt();
    }

    public void handlePacketPlaySend(final PacketEvent event) {}
    public void handlePacketPlayerReceive(final PacketEvent event) {}
    public void handleRelMove(final RelMovePacketWrapper event) {}
}