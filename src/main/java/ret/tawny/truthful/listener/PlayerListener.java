package ret.tawny.truthful.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.ItemStack;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.data.DataManager;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.action.PlayerItemSwitchPacketWrapper;
import ret.tawny.truthful.wrapper.impl.server.position.SetPositionPacketWrapper;

public final class PlayerListener implements Listener {

    private final DataManager dataManager;

    public PlayerListener() {
        this.dataManager = Truthful.getInstance().getDataManager();
        Bukkit.getPluginManager().registerEvents(this, Truthful.getInstance().getPlugin());
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.dataManager.enter(player);

        // --- BRAND DETECTION FIX ---
        // Check if the brand packet arrived before the player data was created.
        if (PacketListener.pendingBrands.containsKey(player.getUniqueId())) {
            String brand = PacketListener.pendingBrands.remove(player.getUniqueId());
            PlayerData data = this.dataManager.getPlayerData(player);

            if (data != null) {
                data.setClientBrand(brand);

                // Construct and send the broadcast message using the config
                String msg = Truthful.getInstance().getConfiguration().getBrandMessage()
                        .replace("%player%", player.getName())
                        .replace("%brand%", brand);

                PacketListener.broadcastBrand(msg);
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.dataManager.eliminate(event.getPlayer());
        // Clean up any pending brands to prevent memory leaks
        PacketListener.pendingBrands.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            PlayerData data = this.dataManager.getPlayerData(player);
            if (data != null) {
                // Record the tick when they exited the boat/cart for exemptions
                data.setLastVehicleExitTick(data.getTicksTracked());
            }
        }
    }

    @EventHandler
    public void onAttack(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player damager)) return;
        if (Truthful.getInstance().isBedrockPlayer(damager)) return;

        final PlayerData data = this.dataManager.getPlayerData(damager);
        if (data == null) return;

        data.setLastTarget(event.getEntity());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // Track firework usage for Elytra check exemptions
        if (item != null && item.getType().name().contains("FIREWORK")) {
            final PlayerData data = this.dataManager.getPlayerData(player);
            if (data != null) {
                data.setLastFireworkTick(data.getTicksTracked());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        // If a plugin cancels a block place, it creates a "Ghost Block" for the client.
        // We track this to prevent movement false flags when they glitch inside it.
        if (event.isCancelled()) {
            PlayerData data = this.dataManager.getPlayerData(event.getPlayer());
            if (data != null) {
                data.setLastGhostBlockTick(data.getTicksTracked());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        // Same logic as block place cancellation
        if (event.isCancelled()) {
            PlayerData data = this.dataManager.getPlayerData(event.getPlayer());
            if (data != null) {
                data.setLastGhostBlockTick(data.getTicksTracked());
            }
        }
    }

    public void onPacket(final PacketEvent packetEvent) {
        // Bedrock players are handled by Floodgate, so we ignore their packets here
        if (Truthful.getInstance().isBedrockPlayer(packetEvent.getPlayer())) return;

        Truthful.getInstance().getScheduler().onPacket(packetEvent);

        final Player player = packetEvent.getPlayer();
        final PlayerData playerData = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (playerData == null) return;

        if (packetEvent.getPacketType().isClient()) {
            if (packetEvent.getPacketType().equals(PacketType.Play.Client.HELD_ITEM_SLOT)) {
                final PlayerItemSwitchPacketWrapper itemSwitchPacketWrapper = new PlayerItemSwitchPacketWrapper(packetEvent);
                playerData.setLastSlot(playerData.getCurrentSlot());
                playerData.setCurrentSlot(itemSwitchPacketWrapper.getSlot());
                playerData.setLastSlotSwitchTime(System.currentTimeMillis());
            } else if (packetEvent.getPacketType().equals(PacketType.Play.Client.BLOCK_PLACE)) {
                playerData.setLastBlockPlaceTime(System.currentTimeMillis());
                playerData.setLastBlockPlaceTick(playerData.getTicksTracked());
            }
        } else {
            if (packetEvent.getPacketType().equals(PacketType.Play.Server.POSITION)) {
                final SetPositionPacketWrapper setPositionPacketWrapper = new SetPositionPacketWrapper(packetEvent);
                playerData.acceptTeleport(setPositionPacketWrapper);
            }
        }
    }
}