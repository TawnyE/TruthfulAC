package ret.tawny.truthful.listener;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.registry.CheckRegistry;
import ret.tawny.truthful.config.api.Configuration;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.ChunkSnapshot;
import ret.tawny.truthful.utils.world.BlockWriteDedup;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TruthfulPacketListener extends PacketListenerAbstract {

    public static final Map<UUID, String> pendingBrands = new ConcurrentHashMap<>();
    private static final java.util.Set<String> liveChunkBuildsInFlight = ConcurrentHashMap.newKeySet();

    private static final java.util.concurrent.ConcurrentHashMap<org.bukkit.Material, Integer> MATERIAL_STATE_IDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int globalIdFor(org.bukkit.Material material) {
        return MATERIAL_STATE_IDS.computeIfAbsent(material, m -> {
            try {
                com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState state =
                        com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
                                .getByString("minecraft:" + m.name().toLowerCase(java.util.Locale.ROOT));
                return (state != null && state.getGlobalId() != 0) ? state.getGlobalId() : 0;
            } catch (Throwable t) {
                return 0;
            }
        });
    }

    /**
     * Chunks that streamed in before PlayerData existed (login burst) never enter
     * the packet-fed cache, leaving permanent holes around spawn -> UNLOADED_CHUNK
     * false flags. Fill the hole from the live world on the player's region thread.
     *
     * Hardened for the far-position disabler: the world UID comes from the
     * player's own cache (no Bukkit world reads from netty threads, illegal on
     * Folia), and each player is rate-limited to a few requests per window so
     * rotating ghost chunks cannot task-spam the region scheduler. Missed
     * chunks simply retry on the next packet, so legit holes still fill.
     */
    public static void requestLiveChunkBuild(final PlayerData data, final int chunkX, final int chunkZ) {
        final Player player = data.getPlayer();
        final UUID worldUid = data.getWorldCache().getWorldUid();

        // Per-player rate limit: at most 4 build requests per 2 seconds.
        final long now = System.nanoTime();
        final java.util.Deque<Long> stamps = LIVE_BUILD_STAMPS.computeIfAbsent(player.getUniqueId(), k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        stamps.addLast(now);
        while (!stamps.isEmpty() && now - stamps.peekFirst() > 2_000_000_000L) {
            stamps.pollFirst();
        }
        if (stamps.size() > 4) return;

        final String key = worldUid + ":" + chunkX + ":" + chunkZ;
        if (!liveChunkBuildsInFlight.add(key)) return;

        Truthful.getInstance().getServerScheduler().runRegion(player, () -> {
            try {
                if (!player.isOnline()) return;
                org.bukkit.World world = player.getWorld();
                if (!world.isChunkLoaded(chunkX, chunkZ)) return;
                if (Truthful.getInstance().getGlobalWorldCache().getChunk(worldUid, chunkX, chunkZ) != null) return;

                // One NMS snapshot copy instead of ~98k getBlock() calls -
                // the old per-block path was the dominant cost of filling
                // login-race holes around spawn. Empty sections are skipped
                // wholesale.
                org.bukkit.Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                org.bukkit.ChunkSnapshot bukkitSnapshot = chunk.getChunkSnapshot(false, false, false);
                int minY = world.getMinHeight();
                int maxY = world.getMaxHeight();
                int sections = (maxY - minY) >> 4;

                ChunkSnapshot snapshot = new ChunkSnapshot();
                int nonAir = 0;
                for (int sy = 0; sy < sections; sy++) {
                    if (bukkitSnapshot.isSectionEmpty(sy)) continue;
                    int sectionBaseY = minY + (sy << 4);
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int yy = 0; yy < 16; yy++) {
                                int wy = sectionBaseY + yy;
                                org.bukkit.Material material = bukkitSnapshot.getBlockType(x, wy, z);
                                if (material == null || material.isAir()) continue;
                                // Cached Material -> globalId: one registry hit
                                // per material type ever, not per block.
                                int globalId = globalIdFor(material);
                                if (globalId != 0) {
                                    snapshot.setBlock(chunkX * 16 + x, wy, chunkZ * 16 + z, globalId);
                                    nonAir++;
                                }
                            }
                        }
                    }
                }
                // A snapshot with ZERO blocks is a build race (chunk generation
                // not finished): caching it permanently blinds that chunk -
                // every read returns air while the player stands on real
                // terrain (fabricated hover/ground flags). Drop it and let
                // the next request retry.
                if (nonAir > 0) {
                    Truthful.getInstance().getGlobalWorldCache().addChunk(worldUid, chunkX, chunkZ, snapshot);
                } else {
                    Truthful.getInstance().getGlobalWorldCache().removeChunk(worldUid, chunkX, chunkZ);
                }
            } catch (Throwable t) {
                Truthful.getInstance().getPlugin().getLogger()
                        .warning("[WorldCache] Live chunk build failed for " + key + ": " + t.getMessage());
            } finally {
                liveChunkBuildsInFlight.remove(key);
            }
        });
    }

    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.Deque<Long>> LIVE_BUILD_STAMPS =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Packet-path error throttle: a hostile packet stream must not be able to
    // spam the console (or cancel framework work) by throwing repeatedly.
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> LAST_PACKET_ERROR =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void reportPacketError(final Player player, final String phase, final Throwable throwable) {
        try {
            final long now = System.currentTimeMillis();
            final Long last = LAST_PACKET_ERROR.get(player.getUniqueId());
            if (last != null && now - last < 10_000L) return;
            LAST_PACKET_ERROR.put(player.getUniqueId(), now);
            Truthful.getInstance().getPlugin().getLogger().warning(
                    "[Truthful] Packet " + phase + " handler failed for " + player.getName()
                            + " (suppressed for 10s): " + throwable);
        } catch (Throwable ignored) {
        }
    }

    /** Per-session listener state cleanup, called from PlayerListener.onQuit. */
    public static void onPlayerQuit(final UUID uuid) {
        if (uuid == null) return;
        LIVE_BUILD_STAMPS.remove(uuid);
        LAST_PACKET_ERROR.remove(uuid);
    }

    private static final Set<String> chunkBuildsInFlight = ConcurrentHashMap.newKeySet();

    private final CheckRegistry checkManager;

    public TruthfulPacketListener(final CheckRegistry checkManager) {
        super(PacketListenerPriority.NORMAL);
        this.checkManager = checkManager;
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Object rawPlayer = event.getPlayer();
        if (!(rawPlayer instanceof Player player)) return;

        final PacketTypeCommon type = event.getPacketType();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        // Framework-level containment: a malformed/hostile packet that makes
        // a wrapper throw used to escape into PacketEvents (disconnect/log)
        // and could skip movement-state advancement. A throw now logs
        // (rate-limited) and the session keeps its state consistent.
        try {
        if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
            WrapperPlayClientTeleportConfirm confirm = new WrapperPlayClientTeleportConfirm(event);
            data.handleTeleportConfirm(confirm.getTeleportId());
        }

        if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            data.setCurrentBlockPlacement(
                    new ret.tawny.truthful.wrapper.impl.client.action.PlayerBlockPlacePacketWrapper(event));
        }

        if (type == PacketType.Play.Client.PONG) {
            WrapperPlayClientPong pong = new WrapperPlayClientPong(event);
            final int id = pong.getId();
            if (id >= Short.MIN_VALUE && id <= Short.MAX_VALUE) {
                data.handleTransaction((short) id);
            }
        } else if (type == PacketType.Play.Client.WINDOW_CONFIRMATION) {
            WrapperPlayClientWindowConfirmation transaction = new WrapperPlayClientWindowConfirmation(event);
            data.handleTransaction(transaction.getActionId());
        }

        if (type instanceof PacketType.Play.Client) {
            Truthful.getInstance().getPlayerListener().onPacket(event, (PacketType.Play.Client) type);
        }

        final boolean isRelMove = RelMovePacketWrapper.isRelMove(type);

        if (!isRelMove) {
            checkManager.getPacketChecks().forEach(check -> {
                if (!check.canCheck(player)) return;
                try {
                    check.handlePacketPlayerReceive(event);
                } catch (Throwable t) {
                    Truthful.getInstance().getPlugin().getLogger().warning(
                            "[CheckDispatch] " + check.getClass().getSimpleName()
                                    + " failed in handlePacketPlayerReceive: " + t.getMessage());
                }
            });
            } else {
                if (type instanceof PacketType.Play.Client) {
                    Object wrapper = createMovementWrapper(event, type);
                    if (wrapper != null) {
                        final RelMovePacketWrapper relMovePacketWrapper = new RelMovePacketWrapper(
                                wrapper, player, (PacketType.Play.Client) type, data);

                        // Position-sanity gate - the far-position disabler fix.
                        // An impossible claim (non-finite, out-of-bounds, or a
                        // teleport-distance jump no pending teleport explains)
                        // must never reach the trackers NOR the server: the
                        // server's correction teleports around it are exactly
                        // what the disabler feeds on, and the claimed position
                        // used to flip the chunk gate that silences every
                        // check. Drop it and flag.
                        if (relMovePacketWrapper.isPositionUpdate()
                                && !ret.tawny.truthful.utils.bedrock.BedrockUtils.isBedrock(player)
                                && data.isImpossiblePositionClaim(
                                        relMovePacketWrapper.getX(), relMovePacketWrapper.getY(), relMovePacketWrapper.getZ())) {
                            if (ret.tawny.truthful.checks.impl.packet.badpacket.BadPacketI.shouldRejectPackets(player)) {
                                event.setCancelled(true);
                            }
                            double gdx = relMovePacketWrapper.getX() - data.getX();
                            double gdy = relMovePacketWrapper.getY() - data.getY();
                            double gdz = relMovePacketWrapper.getZ() - data.getZ();
                            ret.tawny.truthful.checks.impl.packet.badpacket.BadPacketI.noteImpossibleClaim(data,
                                    Math.sqrt(gdx * gdx + gdy * gdy + gdz * gdz));
                            return;
                        }

                    try {
                        data.update(relMovePacketWrapper);

                        // Feed movement & rotation data into the Telemetry Recorder for AI analysis
                        if (Truthful.getInstance().getTelemetryManager() != null) {
                            Truthful.getInstance().getTelemetryManager().onPlayerTick(data);
                        }

                        if (!data.isServerFrozen()) {
                            checkManager.getMovementChecks().forEach(check -> {
                                if (check.canCheck(player)) {
                                    try {
                                        check.handleRelMove(relMovePacketWrapper);
                                    } catch (Throwable t) {
                                        Truthful.getInstance().getPlugin().getLogger().warning(
                                                "[CheckDispatch] " + check.getClass().getSimpleName()
                                                        + " failed in handleRelMove: " + t.getMessage());
                                    }
                                }
                            });

                            // Packet checks must ALSO see movement packets: state-assertion
                            // checks (e.g. BadPacketA flying-without-allow) are per-packet
                            // by contract and silently never fired otherwise.
                            checkManager.getPacketChecks().forEach(check -> {
                                if (!check.canCheck(player)) return;
                                try {
                                    check.handlePacketPlayerReceive(event);
                                } catch (Throwable t) {
                                    Truthful.getInstance().getPlugin().getLogger().warning(
                                            "[CheckDispatch] " + check.getClass().getSimpleName()
                                                    + " failed in handlePacketPlayerReceive: " + t.getMessage());
                                }
                            });
                        }
                    } finally {
                        data.finishMovementTick();
                        data.advanceVelocityState(relMovePacketWrapper.isPositionUpdate());
                    }
                }
            }
        }
        } catch (Throwable t) {
            reportPacketError(player, "receive", t);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        Object rawPlayer = event.getPlayer();
        if (!(rawPlayer instanceof Player player)) return;

        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null) return;

        // Same containment as the receive path: send-side wrappers parse
        // server packets, and one bad packet must not escape into PacketEvents
        // or kill the rest of the send pipeline.
        try {
        Truthful.getInstance().getPlayerListener().onPacket(event);

        // Processor gating: the world model and the entity tracker are only
        // maintained when at least one ENABLED check actually consumes them
        // (aggregated by the registry). Transactions, self-velocity,
        // explosion marks and teleport bookkeeping always run - they carry
        // packet ordering and exemption state that every profile needs.
        final boolean worldActive = checkManager.isWorldConsumed();
        final boolean entitiesActive = checkManager.isEntityTrackingConsumed();

        // World-model packets: only decoded/tracked while a world-consuming
        // check is enabled. The guards are branch-local (never return out of
        // the listener) so downstream consumers stay reachable.
        final PacketTypeCommon sendType = event.getPacketType();
        if (worldActive && sendType == PacketType.Play.Server.CHUNK_DATA) {
            WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
            int chunkX = wrapper.getColumn().getX();
            int chunkZ = wrapper.getColumn().getZ();
            UUID worldUid = player.getWorld().getUID();

            final String inFlightKey = worldUid + ":" + chunkX + ":" + chunkZ;
            final boolean hasCachedChunk = Truthful.getInstance().getGlobalWorldCache().getChunk(worldUid, chunkX, chunkZ) != null;
            final boolean shouldBuild = !hasCachedChunk && chunkBuildsInFlight.add(inFlightKey);

            int minHeight = data.getWorldMinHeight();

            if (shouldBuild) {
                BaseChunk[] chunks = wrapper.getColumn().getChunks();

                Truthful.getInstance().getServerScheduler().runWorldCache(() -> {
                    try {
                        if (Truthful.getInstance().getGlobalWorldCache().getChunk(worldUid, chunkX, chunkZ) == null) {
                            ChunkSnapshot snapshot = new ChunkSnapshot();

                            for (int i = 0; i < chunks.length; i++) {
                                BaseChunk section = chunks[i];
                                if (section == null) continue;
                                int sectionBaseY = (i << 4) + minHeight;

                                for (int x = 0; x < 16; x++) {
                                    int worldX = chunkX * 16 + x;
                                    for (int z = 0; z < 16; z++) {
                                        int worldZ = chunkZ * 16 + z;
                                        for (int y = 0; y < 16; y++) {
                                            int gid = section.get(x, y, z).getGlobalId();
                                            if (gid != 0) {
                                                snapshot.setBlock(worldX, sectionBaseY + y, worldZ, gid);
                                            }
                                        }
                                    }
                                }
                            }
                            Truthful.getInstance().getGlobalWorldCache().addChunk(worldUid, chunkX, chunkZ, snapshot);
                        }
                    } catch (Throwable t) {
                        Truthful.getInstance().getPlugin().getLogger()
                                .warning("[WorldCache] Failed to build chunk " + inFlightKey + ": " + t.getMessage());
                    } finally {
                        chunkBuildsInFlight.remove(inFlightKey);
                    }
                });
            }
        } else if (worldActive && sendType == PacketType.Play.Server.BLOCK_CHANGE) {
            WrapperPlayServerBlockChange wrapper = new WrapperPlayServerBlockChange(event);
            Vector3i pos = wrapper.getBlockPosition();
            java.util.List<long[]> batch = new java.util.ArrayList<>(1);
            applyBlockChange(player, data, event, pos.getX(), pos.getY(), pos.getZ(), wrapper.getBlockId(), batch);
            finishBlockChangeBatch(data, event, batch);
        } else if (worldActive && sendType == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            WrapperPlayServerMultiBlockChange wrapper = new WrapperPlayServerMultiBlockChange(event);
            java.util.List<long[]> batch = new java.util.ArrayList<>();
            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : wrapper.getBlocks()) {
                applyBlockChange(player, data, event, block.getX(), block.getY(), block.getZ(), block.getBlockId(), batch);
            }
            finishBlockChangeBatch(data, event, batch);
        } else if (worldActive && sendType == PacketType.Play.Server.UNLOAD_CHUNK) {
            WrapperPlayServerUnloadChunk wrapper = new WrapperPlayServerUnloadChunk(event);
            int ux = wrapper.getChunkX(), uz = wrapper.getChunkZ();
            data.getWorldCache().removeChunk(ux, uz);
            // Do NOT evict from the shared global cache here: this unload only
            // concerns THIS player. Other players in the same world still need
            // the chunk; the global cache evicts by capacity/world-empty instead.
        }

        // Entity-tracker packets: same branch-local gating. Self-velocity,
        // explosion marks and keep-alive accounting below always run.
        if (entitiesActive && sendType == PacketType.Play.Server.SPAWN_PLAYER) {
            WrapperPlayServerSpawnPlayer wrapper = new WrapperPlayServerSpawnPlayer(event);
            Truthful.getInstance().getCompensationTracker().handleSpawn(wrapper.getEntityId(), wrapper.getUUID(),
                    wrapper.getPosition().x, wrapper.getPosition().y, wrapper.getPosition().z, 0.6, 1.8, true);
        } else if (entitiesActive && sendType == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity wrapper = new WrapperPlayServerSpawnEntity(event);
            UUID uuid = wrapper.getUUID().orElse(UUID.randomUUID());
            Truthful.getInstance().getCompensationTracker().handleSpawn(wrapper.getEntityId(), uuid,
                    wrapper.getPosition().x, wrapper.getPosition().y, wrapper.getPosition().z, 0.6, 1.8, false);
        } else if (entitiesActive && sendType == PacketType.Play.Server.ENTITY_TELEPORT) {
            WrapperPlayServerEntityTeleport wrapper = new WrapperPlayServerEntityTeleport(event);
            Truthful.getInstance().getCompensationTracker().handleTeleport(wrapper.getEntityId(),
                    wrapper.getPosition().x, wrapper.getPosition().y, wrapper.getPosition().z);
        } else if (entitiesActive && sendType == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
            WrapperPlayServerEntityRelativeMove wrapper = new WrapperPlayServerEntityRelativeMove(event);
            Truthful.getInstance().getCompensationTracker().handleRelMove(wrapper.getEntityId(),
                    wrapper.getDeltaX(), wrapper.getDeltaY(), wrapper.getDeltaZ());
        } else if (entitiesActive && sendType == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
            // Same fields, plus yaw/pitch - must use the matching wrapper so the
            // read consumes the full packet and nothing else.
            WrapperPlayServerEntityRelativeMoveAndRotation wrapper = new WrapperPlayServerEntityRelativeMoveAndRotation(event);
            Truthful.getInstance().getCompensationTracker().handleRelMove(wrapper.getEntityId(),
                    wrapper.getDeltaX(), wrapper.getDeltaY(), wrapper.getDeltaZ());
        } else if (entitiesActive && sendType == PacketType.Play.Server.DESTROY_ENTITIES) {
            WrapperPlayServerDestroyEntities wrapper = new WrapperPlayServerDestroyEntities(event);
            for (int id : wrapper.getEntityIds()) {
                Truthful.getInstance().getCompensationTracker().handleDestroy(id);
            }
        }

        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            // Self-velocity always runs: the queue carries exemption and
            // explosion state even for clicks-only profiles. Velocity for
            // OTHER entities feeds the compensated tracker only.
            WrapperPlayServerEntityVelocity velocity = new WrapperPlayServerEntityVelocity(event);
            if (velocity.getEntityId() == player.getEntityId()) {
                com.github.retrooper.packetevents.util.Vector3d vel = velocity.getVelocity();
                if (Math.abs(vel.x) > 0.001 || Math.abs(vel.y) > 0.001 || Math.abs(vel.z) > 0.001) {
                    handleVelocity(player, data, new Vector(vel.x, vel.y, vel.z), event, false);
                }
            } else if (entitiesActive) {
                com.github.retrooper.packetevents.util.Vector3d v = velocity.getVelocity();
                Truthful.getInstance().getCompensationTracker().handleVelocity(velocity.getEntityId(), v.x, v.y, v.z);
            }
        }

        if (event.getPacketType() == PacketType.Play.Server.KEEP_ALIVE) {
            // Feed BadPacketT's reply-vs-asked accounting. Read-only.
            try {
                ret.tawny.truthful.checks.impl.packet.badpacket.BadPacketT
                        .noteServerKeepAlive(player.getUniqueId());
            } catch (Throwable ignored) {}
        }

        if (event.getPacketType() == PacketType.Play.Server.EXPLOSION) {
            WrapperPlayServerExplosion explosion = new WrapperPlayServerExplosion(event);
            com.github.retrooper.packetevents.util.Vector3f push = explosion.getPlayerMotion();

            if (push != null && (Math.abs(push.x) > 0.001 || Math.abs(push.y) > 0.001 || Math.abs(push.z) > 0.001)) {
                // Only a REAL push to this player arms the explosion grace:
                // remote/zero-motion explosions used to suppress lagbacks on
                // demand (crystal spam was a soft no-lagback mode).
                data.markExplosionPacket();
                handleVelocity(player, data, new Vector(push.x, push.y, push.z), event, true);
            }
        }

        // Ridden-vehicle link + the vehicle entity's rotation. Boat physics
        // steers along the BOAT yaw, which is not the rider's look, so this is
        // tracked unconditionally (not gated on entity tracking).
        if (sendType == PacketType.Play.Server.SET_PASSENGERS) {
            WrapperPlayServerSetPassengers passengers = new WrapperPlayServerSetPassengers(event);
            int vehicleId = passengers.getEntityId();
            boolean self = false;
            if (passengers.getPassengers() != null) {
                for (int p : passengers.getPassengers()) {
                    if (p == player.getEntityId()) { self = true; break; }
                }
            }
            if (self) {
                data.setVehicleEntityId(vehicleId);
                data.setVehicleRotation(data.getYaw(), data.getPitch());
            } else if (data.getVehicleEntityId() == vehicleId) {
                data.setVehicleEntityId(-1);
            }
        } else if (data.getVehicleEntityId() != -1) {
            final int veh = data.getVehicleEntityId();
            if (sendType == PacketType.Play.Server.ENTITY_HEAD_LOOK) {
                WrapperPlayServerEntityHeadLook w = new WrapperPlayServerEntityHeadLook(event);
                if (w.getEntityId() == veh) data.setVehicleHeadYaw(w.getHeadYaw());
            } else if (sendType == PacketType.Play.Server.ENTITY_ROTATION) {
                WrapperPlayServerEntityRotation w = new WrapperPlayServerEntityRotation(event);
                if (w.getEntityId() == veh) data.setVehicleRotation(w.getYaw(), w.getPitch());
            } else if (sendType == PacketType.Play.Server.ENTITY_TELEPORT) {
                WrapperPlayServerEntityTeleport w = new WrapperPlayServerEntityTeleport(event);
                if (w.getEntityId() == veh) data.setVehicleRotation(w.getYaw(), w.getPitch());
            }
        }

        checkManager.getSendPacketChecks().forEach(check -> {
            if (check.canCheck(player)) {
                try {
                    check.handlePacketPlaySend(event);
                } catch (Throwable t) {
                    reportPacketError(player, "send-check", t);
                }
            }
        });
        } catch (Throwable t) {
            reportPacketError(player, "send", t);
        }
    }

    private void handleVelocity(Player player, PlayerData data, Vector vel, PacketSendEvent event, boolean isExplosion) {
        int startId = data.getNextTransactionId();
        int endId = data.getNextTransactionId();

        event.getTasksAfterSend().add(() -> {
            if (player.isOnline() && Truthful.getInstance().getConfiguration().isTransactionPingEnabled()) {
                data.recordTransactionSent((short) startId);
                sendTransactionPacket(player, startId);
                data.recordTransactionSent((short) endId);
                sendTransactionPacket(player, endId);
            }
        });

        data.getVelocities().addVelocity(vel, startId, endId, isExplosion);
        data.setLastVelocityTick(data.getTicksTracked());
    }

    private void sendTransactionPacket(Player player, int id) {
        // 1.17+ servers: the modern Ping packet doubles as our transaction ack.
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerPing(id));
    }

    /**
     * Transaction-bracketed block application (Grim's world model): the OLD
     * state is pinned into this player's latency view at send time, the new
     * state goes into the shared cache immediately, and a transaction sent
     * after the packet reveals the new state only once this client acks it.
     * The engine always simulates the world the player actually sees.
     */
    private void applyBlockChange(Player player, PlayerData data, PacketSendEvent event,
                                   int x, int y, int z, int blockId, java.util.List<long[]> batch) {
        ret.tawny.truthful.data.world.CompensatedWorld view = data.getWorldCache();
        boolean bracketed = Truthful.getInstance().getConfiguration().isTransactionPingEnabled();

        if (bracketed) {
            int oldId = view.getSharedBlockId(x, y, z);
            view.recordStaleView(packLocal(x, y, z), oldId);
            batch.add(new long[]{((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF)});
        }

        // Shared-cache write, deduped per world per tick so broadcast storms
        // (FAWE etc.) cost one write while every player still gets their own
        // latency pin above.
        if (!BlockWriteDedup.tryClaim(player.getWorld().getUID(), x, y, z, blockId)) {
            return;
        }
        view.updateBlock(x, y, z, blockId);
    }

    private void finishBlockChangeBatch(PlayerData data, PacketSendEvent event, java.util.List<long[]> batch) {
        if (batch.isEmpty()) return;
        long[] positions = new long[batch.size()];
        for (int i = 0; i < batch.size(); i++) positions[i] = batch.get(i)[0];

        int id = data.getNextTransactionId();
        event.getTasksAfterSend().add(() -> {
            if (data.getPlayer().isOnline()) {
                data.recordTransactionSent((short) id);
                PacketEvents.getAPI().getPlayerManager().sendPacket(data.getPlayer(), new WrapperPlayServerPing(id));
            }
        });
        data.getWorldCache().registerFlush((short) id, positions);
    }

    private static long packLocal(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }

    public static void updatePlayerBrand(Player player, String brand) {
        if (player == null || brand == null || brand.isBlank()) return;

        final Truthful truthful = Truthful.getInstance();
        if (truthful.getPlugin() == null || truthful.getDataManager() == null) {
            pendingBrands.put(player.getUniqueId(), brand);
            return;
        }

        PlayerData data = truthful.getDataManager().getPlayerData(player);
        if (data == null) {
            pendingBrands.put(player.getUniqueId(), brand);
            return;
        }

        String currentBrand = data.getClientBrand();
        if (!(currentBrand.equals("Unknown") || (!brand.equalsIgnoreCase("vanilla") && !brand.equalsIgnoreCase(currentBrand)))) {
            return;
        }

        Configuration config = truthful.getConfiguration();
        if (config != null && config.isClientBlockerEnabled()) {
            String checkBrand = brand.toLowerCase();
            for (String blockedBrand : config.getBlockedBrands()) {
                if (checkBrand.contains(blockedBrand.toLowerCase())) {
                    Truthful.getInstance().getServerScheduler().runRegion(player, () -> {
                        if (player.isOnline()) {
                            player.kickPlayer(config.getClientBlockerMessage());
                        }
                    });
                    return;
                }
            }
        }

        data.setClientBrand(brand);
    }

    private Object createMovementWrapper(PacketReceiveEvent event, PacketTypeCommon type) {
        if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION)
            return new WrapperPlayClientPlayerPositionAndRotation(event);
        else if (type == PacketType.Play.Client.PLAYER_POSITION)
            return new WrapperPlayClientPlayerPosition(event);
        else if (type == PacketType.Play.Client.PLAYER_ROTATION)
            return new WrapperPlayClientPlayerRotation(event);
        else if (type == PacketType.Play.Client.PLAYER_FLYING)
            return new WrapperPlayClientPlayerFlying(event);
        else if (type == PacketType.Play.Client.VEHICLE_MOVE)
            return new WrapperPlayClientVehicleMove(event);
        return null;
    }
}
