package ret.tawny.truthful.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import io.netty.buffer.ByteBuf;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.registry.CheckRegistry;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.sync.VelocityQueue;
import ret.tawny.truthful.utils.ServerUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketListener {

    // Store brands temporarily if PlayerData isn't ready yet (Race Condition Fix)
    public static final Map<UUID, String> pendingBrands = new ConcurrentHashMap<>();

    public PacketListener(final CheckRegistry checkManager) {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(Truthful.getInstance().getPlugin(), ListenerPriority.HIGH,
                PacketType.Play.Client.ABILITIES,
                PacketType.Play.Client.BLOCK_DIG,
                PacketType.Play.Client.BLOCK_PLACE,
                PacketType.Play.Client.USE_ITEM,
                PacketType.Play.Client.CUSTOM_PAYLOAD,
                PacketType.Play.Client.ENTITY_ACTION,
                PacketType.Play.Client.FLYING,
                PacketType.Play.Client.POSITION,
                PacketType.Play.Client.POSITION_LOOK,
                PacketType.Play.Client.LOOK,
                PacketType.Play.Client.HELD_ITEM_SLOT,
                PacketType.Play.Client.ARM_ANIMATION,
                PacketType.Play.Client.SPECTATE,
                PacketType.Play.Client.TRANSACTION,
                PacketType.Play.Server.ENTITY_VELOCITY,
                PacketType.Play.Server.ENTITY_TELEPORT,
                PacketType.Play.Server.POSITION
        ) {
            @Override
            public void onPacketReceiving(final PacketEvent event) {
                final Player player = event.getPlayer();
                if (player == null) return;

                // --- BRAND DETECTION START ---
                // We handle Brand separately because it might arrive before PlayerData is initialized.
                if (event.getPacketType() == PacketType.Play.Client.CUSTOM_PAYLOAD) {
                    PacketContainer packet = event.getPacket();
                    String channel = "unknown";

                    // Robust Channel Name Reading (Legacy vs Modern)
                    try {
                        if (packet.getMinecraftKeys().size() > 0) {
                            channel = packet.getMinecraftKeys().read(0).getKey(); // Modern (minecraft:brand)
                        } else if (packet.getStrings().size() > 0) {
                            channel = packet.getStrings().read(0); // Legacy (MC|Brand)
                        }
                    } catch (Exception ignored) {}

                    if ("brand".equals(channel) || "MC|Brand".equals(channel)) {
                        String cleanBrand = null;

                        try {
                            // Try reading as String first (Some 1.20+ setups)
                            if (packet.getStrings().size() > 0 && "MC|Brand".equals(channel)) {
                                // Legacy might put the data in strings? Usually bytes though.
                            }

                            // Decoding the payload
                            byte[] payload = null;
                            if (packet.getByteArrays().size() > 0) {
                                payload = packet.getByteArrays().read(0);
                            } else if (packet.getSpecificModifier(ByteBuf.class).size() > 0) {
                                ByteBuf buf = packet.getSpecificModifier(ByteBuf.class).read(0).copy();
                                try {
                                    if (buf.isReadable()) {
                                        payload = new byte[buf.readableBytes()];
                                        buf.readBytes(payload);
                                    }
                                } finally {
                                    buf.release();
                                }
                            }

                            if (payload != null) {
                                // Simple string decoding, removing non-printable chars
                                String raw = new String(payload, StandardCharsets.UTF_8);
                                cleanBrand = raw.replaceAll("[^\\x20-\\x7E]", "").trim();

                                // Remove weird length prefixes often found in legacy packet buffers if decoding failed manually
                                if (cleanBrand.length() > 30) cleanBrand = cleanBrand.substring(0, 30);
                            }
                        } catch (Exception e) {
                            cleanBrand = "ErrorDecoding";
                        }

                        if (cleanBrand != null && !cleanBrand.isEmpty()) {
                            PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

                            // Prepare Notification
                            String msg = Truthful.getInstance().getConfiguration().getBrandMessage()
                                    .replace("%player%", player.getName())
                                    .replace("%brand%", cleanBrand);

                            if (data != null) {
                                // Data exists, set it now
                                data.setClientBrand(cleanBrand);
                                broadcastBrand(msg);
                            } else {
                                // Data missing (Join Race Condition), store for PlayerListener
                                pendingBrands.put(player.getUniqueId(), cleanBrand);
                            }
                        }
                    }
                }
                // --- BRAND DETECTION END ---

                // Standard Check Handling
                final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
                if (data == null) return;

                Truthful.getInstance().getPlayerListener().onPacket(event);
                checkManager.getCollection().forEach(check -> check.onPacketPlayerReceive(event));

                if (RelMovePacketWrapper.isRelMove(event.getPacketType())) {
                    final RelMovePacketWrapper relMovePacketWrapper = new RelMovePacketWrapper(event);
                    data.update(relMovePacketWrapper);
                    checkManager.getCollection().forEach(check -> check.onRelMove(relMovePacketWrapper));
                }
            }

            @Override
            public void onPacketSending(final PacketEvent event) {
                final Player player = event.getPlayer();
                if (player == null) return;

                final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
                if (data == null) return;

                if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
                    int entityId = event.getPacket().getIntegers().read(0);

                    if (entityId == player.getEntityId()) {
                        double x = event.getPacket().getIntegers().read(1) / 8000.0D;
                        double y = event.getPacket().getIntegers().read(2) / 8000.0D;
                        double z = event.getPacket().getIntegers().read(3) / 8000.0D;

                        int pingTicks = ServerUtils.getTickDelay(data);
                        data.getVelocities().add(new VelocityQueue.Velocity(new Vector(x, y, z), pingTicks, (short) 0));
                    }
                }

                Truthful.getInstance().getPlayerListener().onPacket(event);
                checkManager.getCollection().forEach(check -> check.onPacketPlaySend(event));
            }
        });
    }

    public static void broadcastBrand(String message) {
        Bukkit.getScheduler().runTask(Truthful.getInstance().getPlugin(), () -> {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("truthful.alerts")) {
                    staff.sendMessage(message);
                }
            }
        });
    }
}