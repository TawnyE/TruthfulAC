package ret.tawny.truthful.listener;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.data.DataManager;
import ret.tawny.truthful.data.ExemptionType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.server.position.SetPositionPacketWrapper;

@SuppressWarnings("deprecation")
public final class PlayerListener implements Listener {

    private final DataManager dataManager;

    public PlayerListener() {
        this.dataManager = Truthful.getInstance().getDataManager();
        Bukkit.getPluginManager().registerEvents(this, Truthful.getInstance().getPlugin());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PlayerData data = this.dataManager.getPlayerData(player);
            if (data != null) data.setInventoryOpen(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            PlayerData data = this.dataManager.getPlayerData(player);
            if (data != null && !data.isInventoryOpen()) data.setInventoryOpen(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PlayerData data = this.dataManager.getPlayerData(player);
            if (data != null) data.setInventoryOpen(false);
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.dataManager.enter(player);

        if (TruthfulPacketListener.pendingBrands.containsKey(player.getUniqueId())) {
            String brand = TruthfulPacketListener.pendingBrands.remove(player.getUniqueId());
            TruthfulPacketListener.updatePlayerBrand(player, brand);
        }

        Truthful.getInstance().getServerScheduler().runRegionLater(player, () -> {
            if (!player.isOnline()) return;

            String brand = null;
            try {
                brand = player.getClientBrandName();
            } catch (Throwable ignored) {}

            if (brand != null && !brand.isEmpty()) {
                TruthfulPacketListener.updatePlayerBrand(player, brand);
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerData data = this.dataManager.getPlayerData(event.getPlayer());
        if (data != null) {
            data.handleWorldChange();
        }
    }

    @EventHandler
    public void onRiptide(PlayerRiptideEvent event) {
        PlayerData data = this.dataManager.getPlayerData(event.getPlayer());
        if (data != null) {
            data.setLastRiptideTick(data.getTicksTracked());
            data.setUsingRiptide(true);
            // Model the launch exactly (look * 3*((1+level)/4)) instead of
            // blanket-exempting the spin window; keep a short ordering window.
            int level = riptideLevel(event.getPlayer());
            if (level > 0) {
                data.armRiptideImpulse(3.0D * ((1.0D + level) / 4.0D));
            }
            data.setExemption(ExemptionType.RIPTIDE, 20);
            data.setVelocityExemption(20);
        }
    }

    private static int riptideLevel(Player player) {
        ItemStack[] hands = {player.getInventory().getItemInMainHand(), player.getInventory().getItemInOffHand()};
        for (ItemStack it : hands) {
            if (it != null && it.getType() == Material.TRIDENT) {
                int lvl = it.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.RIPTIDE);
                if (lvl > 0) return lvl;
            }
        }
        return 0;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.dataManager.eliminate(event.getPlayer());
        TruthfulPacketListener.pendingBrands.remove(event.getPlayer().getUniqueId());
        // Listener-owned static bookkeeping (chunk-build rate limit stamps,
        // packet-error throttle) is per-session, not per-UUID-forever.
        TruthfulPacketListener.onPlayerQuit(event.getPlayer().getUniqueId());

        // Debug/logging subscriptions follow the session, not the UUID. Leaving
        // these in place across quits was a slow leak + hot-path Bukkit lookups
        // for ghosts that will never log back in.
        UUID uuid = event.getPlayer().getUniqueId();
        ret.tawny.truthful.commands.impl.CommandManager.debuggers.remove(uuid);
        ret.tawny.truthful.debug.DebugManager dm = Truthful.getInstance().getDebugManager();
        if (dm != null) dm.stopDebuggingCheck(uuid);
        ret.tawny.truthful.debug.logging.DebugLoggingManager dlm = Truthful.getInstance().getDebugLoggingManager();
        if (dlm != null) dlm.stopLogging(uuid);
        ret.tawny.truthful.debug.telemetry.TelemetryManager tm = Truthful.getInstance().getTelemetryManager();
        if (tm != null && tm.getFlagRecorder() != null) tm.getFlagRecorder().onQuit(uuid);
        // A recorded player who disconnects must not leave an ActiveRecording
        // (and its growing frame list) retained forever - flush and drop it.
        if (tm != null) {
            try {
                tm.stopTelemetryRecording(event.getPlayer());
            } catch (Throwable ignored) {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(final PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PlayerData data = this.dataManager.getPlayerData(player);
        if (data != null) {
            Truthful.getInstance().getServerScheduler().runRegionLater(player, data::resetTransientState, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        PlayerData data = this.dataManager.getPlayerData(event.getPlayer());
        if (data != null) {
            data.updateStateCache();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        PlayerData data = this.dataManager.getPlayerData(event.getPlayer());
        if (data != null) {
            data.updateStateCache();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        PlayerData data = this.dataManager.getPlayerData(event.getPlayer());
        if (data != null) {
            data.updateStateCache();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (event.isCancelled()) return;

            boolean physicsDamage = switch (event.getCause()) {
                case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, ENTITY_EXPLOSION, BLOCK_EXPLOSION, THORNS, CUSTOM -> true;
                default -> false;
            };

            if (physicsDamage) {
                PlayerData data = this.dataManager.getPlayerData(player);
                if (data != null) {
                    data.setLastDamageTick(data.getTicksTracked());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            PlayerData data = this.dataManager.getPlayerData(player);
            if (data != null) {
                data.setNearVehicle(true);
                // Server-trusted vehicle stamps. The state cache refresh is
                // delayed one tick so the server-side passenger list has
                // settled; the tick stamp closes the gap meanwhile.
                data.setLastVehicleTick(data.getTicksTracked());
                Truthful.getInstance().getServerScheduler().runRegionLater(player, data::updateStateCache, 1L);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            PlayerData data = this.dataManager.getPlayerData(player);
            if (data != null) {
                data.setLastVehicleExitTick(data.getTicksTracked());
                data.setLastVehicleTick(data.getTicksTracked());
                data.setPreExitVehicleSpeed(event.getVehicle().getVelocity().length());
                data.setNearVehicle(true);
                Truthful.getInstance().getServerScheduler().runRegionLater(player, data::updateStateCache, 1L);
            }
        }
    }

    @EventHandler
    public void onAttack(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player damager)) return;
        if (Truthful.getInstance().isBedrockPlayer(damager)) return;

        final PlayerData data = this.dataManager.getPlayerData(damager);
        if (data != null) {
            data.setLastTarget(event.getEntity());
            data.setLastHitTick(data.getTicksTracked());

            if (data.isInventoryOpen()) data.setInventoryOpen(false);

            ItemStack hand = damager.getInventory().getItemInMainHand();
            if (hand.getType() != Material.AIR && hand.getType().name().contains("MACE")) {
                if (damager.getFallDistance() > 1.5) {
                    data.setExemption(ExemptionType.MACE_SMASH, 30);

                    boolean hasWindBurst = hand.getEnchantments().keySet().stream()
                            .anyMatch(enchant -> enchant.getKey().getKey().contains("wind_burst"));

                    if (hasWindBurst) {
                        data.setExemption(ExemptionType.WIND_CHARGE, 40);
                        data.setVelocityExemption(40);
                    }
                }
            }

            // Spear lunges can fire on hit as well - arm the same bounded
            // window from the attack path so the dash is never judged.
            if (data.isHoldingSpear()) {
                data.armSpearLunge(20);
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.useItemInHand() == Event.Result.DENY) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;

        final PlayerData data = this.dataManager.getPlayerData(player);
        if (data == null) return;

        if (data.isInventoryOpen()) data.setInventoryOpen(false);

        String typeName = item.getType().name();

        if (typeName.contains("FIREWORK") && player.isGliding()) {
            if (data.getTicksTracked() - data.getLastFireworkTick() > 10) {
                data.onFireworkInteraction();
                data.setLastFireworkTick(data.getTicksTracked());
            }
        }

        // Spear-family items: the lunge dash is applied client-side and never
        // arrives as a server velocity packet, so movement checks cannot
        // explain it. Arm a bounded window on use. Detection is MATERIAL
        // based - the old display-name heuristic let anyone with an anvil
        // rename a stick to "spear"/"lunge" and farm velocity immunity by
        // spamming right-click. Custom items that need a dash should send the
        // impulse as a server velocity packet, which the engine models
        // honestly. The charged-release case is handled on RELEASE_USE_ITEM.
        if (typeName.contains("SPEAR") || data.isHoldingSpear()) {
            data.armSpearLunge(20);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) {
            PlayerData data = this.dataManager.getPlayerData(event.getPlayer());
            if (data != null) data.setLastGhostBlockTick(data.getTicksTracked());
        }
    }

    // Pistons move clients without any velocity packet. Record the push
    // direction for nearby players so the movement engine can add a
    // direction-bounded allowance instead of a blanket exemption.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent event) {
        handlePiston(event.getBlock().getLocation(), event.getDirection().getModX(),
                event.getDirection().getModY(), event.getDirection().getModZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent event) {
        // Retraction pulls entities back toward the piston.
        handlePiston(event.getBlock().getLocation(), -event.getDirection().getModX(),
                -event.getDirection().getModY(), -event.getDirection().getModZ());
    }

    private void handlePiston(Location pistonLoc, double dirX, double dirY, double dirZ) {
        for (Player player : pistonLoc.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(pistonLoc) > 20 * 20) continue;
            PlayerData data = this.dataManager.getPlayerData(player);
            if (data != null) {
                data.onPistonPush(dirX, dirY, dirZ);
            }
        }
    }

    public void onPacket(final PacketReceiveEvent event, PacketType.Play.Client type) {
        final Player player = event.getPlayer();
        if (player == null || Truthful.getInstance().isBedrockPlayer(player)) return;

        final PlayerData playerData = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (playerData == null) return;

        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            playerData.setLastWindowClick(System.currentTimeMillis());
            if (!playerData.isInventoryOpen()) playerData.setInventoryOpen(true);
        } else if (type == PacketType.Play.Client.CLIENT_STATUS) {
            WrapperPlayClientClientStatus cmd = new WrapperPlayClientClientStatus(event);
            if (cmd.getAction().name().equals("REQUEST_STATS")) playerData.setInventoryOpen(true);
        } else if (type == PacketType.Play.Client.CLOSE_WINDOW) {
            playerData.setInventoryOpen(false);
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            // Keep inventory open status for PacketOrderE evaluation
            playerData.setDigging(false);
            playerData.setLastAttackPacketTick(playerData.getTicksTracked());
        } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper cached = playerData.getCurrentBlockPlacement();
            if (cached != null) {
                playerData.setLastBlockPlaceTime(System.currentTimeMillis());
                playerData.setLastBlockPlaceTick(playerData.getTicksTracked());
            }
        } else if (type == PacketType.Play.Client.USE_ITEM) {
            // Only set usingItem if holding an actual consumable/slowdown item (food, bow, potion, shield)
            if (playerData.isSlowItem()) {
                playerData.setUsingItem(true, playerData.getTicksTracked());
            }
        } else if (type == PacketType.Play.Client.PLAYER_INPUT) {
            // 1.21.2+: the client reports its exact WASD/jump/shift/sprint
            // state. This collapses the engine's input brute-force to a
            // single exact candidate on modern clients.
            try {
                WrapperPlayClientPlayerInput input = new WrapperPlayClientPlayerInput(event);
                playerData.getActionTracker().setClientInput(
                        input.isForward(), input.isBackward(), input.isLeft(), input.isRight(),
                        input.isJump(), input.isShift(), input.isSprint(),
                        playerData.getTicksTracked());
            } catch (Throwable ignored) {
            }
        } else if (type == PacketType.Play.Client.CLIENT_TICK_END) {
            playerData.getActionTracker().markClientTickEnd(playerData.getTicksTracked());
        } else if (type == PacketType.Play.Client.STEER_VEHICLE) {
            // STEER_VEHICLE is just control input for the physics model - it
            // must NEVER set vehicle membership state. The old code treated
            // the packet as proof of riding, so one forged steer per 10
            // packets kept isInsideVehicle() true forever and skipped most
            // movement/velocity/timer checks. Membership comes from the
            // server-trusted cache refreshed by the vehicle events below.
            try {
                WrapperPlayClientSteerVehicle steer = new WrapperPlayClientSteerVehicle(event);
                playerData.getActionTracker().setVehicleSteer(
                        steer.getForward(), steer.getSideways(), playerData.getTicksTracked());
            } catch (Throwable ignored) {
            }
        }

        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            WrapperPlayClientHeldItemChange itemSwitch = new WrapperPlayClientHeldItemChange(event);
            playerData.setLastSlot(playerData.getCurrentSlot());
            playerData.setCurrentSlot(itemSwitch.getSlot());
            playerData.setLastSlotSwitchTime(System.currentTimeMillis());
            playerData.setUsingItem(false);
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            try {
                WrapperPlayClientPlayerDigging dig = new WrapperPlayClientPlayerDigging(event);
                if (dig.getAction() == DiggingAction.START_DIGGING) {
                    playerData.setDigging(true);
                } else if (dig.getAction() == DiggingAction.FINISHED_DIGGING || dig.getAction() == DiggingAction.CANCELLED_DIGGING) {
                    playerData.setDigging(false);
                } else if (dig.getAction() == DiggingAction.RELEASE_USE_ITEM
                        || dig.getAction() == DiggingAction.DROP_ITEM
                        || dig.getAction() == DiggingAction.DROP_ITEM_STACK) {
                    playerData.setUsingItem(false);

                    // Charged spear lunge: RELEASE_USE_ITEM is the moment the
                    // dash impulse actually fires. The interact-time window
                    // has long lapsed for a held charge, so arm a fresh one
                    // here - the cached "holding a spear" flag is refreshed
                    // on the main thread, never read live from this thread.
                    if (dig.getAction() == DiggingAction.RELEASE_USE_ITEM
                            && playerData.isHoldingSpear()) {
                        playerData.armSpearLunge(40);
                    }
                }
            } catch (Throwable t) {
                Truthful.getInstance().getPlugin().getLogger().warning("[PlayerListener] Failed to parse PLAYER_DIGGING packet: " + t.getMessage());
            }
        } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction action = new WrapperPlayClientEntityAction(event);
            switch (action.getAction()) {
                case START_SPRINTING -> playerData.setSprinting(true);
                case STOP_SPRINTING -> playerData.setSprinting(false);
                case START_SNEAKING -> playerData.setSneaking(true);
                case STOP_SNEAKING -> playerData.setSneaking(false);
                case START_FALL_FLYING -> {
                    if (playerData.canGlide()) {
                        playerData.setGliding(true);
                        playerData.setLastGlideTick(playerData.getTicksTracked());
                    }
                }
            }
        }
    }

    public void onPacket(final PacketSendEvent event) {
        final Player player = event.getPlayer();
        if (player == null || Truthful.getInstance().isBedrockPlayer(player)) return;
        final PlayerData playerData = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (playerData == null) return;

        if (event.getPacketType() == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            WrapperPlayServerPlayerPositionAndLook posLook = new WrapperPlayServerPlayerPositionAndLook(event);
            SetPositionPacketWrapper wrapper = new SetPositionPacketWrapper(
                    posLook, player, PacketType.Play.Server.PLAYER_POSITION_AND_LOOK,
                    playerData.getX(), playerData.getY(), playerData.getZ());
            playerData.acceptTeleport(wrapper);
        }
    }
}