package ret.tawny.truthful.debug.telemetry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.data.PlayerData;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FlagRecorder {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, Long> lastFlagLogTimes = new ConcurrentHashMap<>();

    public void onQuit(UUID uuid) {
        lastFlagLogTimes.remove(uuid);
    }

    public void recordFlag(PlayerData data, String checkName, String details, double buffer, double threshold) {
        if (data == null || data.getPlayer() == null) return;
        Player player = data.getPlayer();

        // Rate limit individual check flag logs to 1 per second per player to prevent log flooding
        long now = System.currentTimeMillis();
        long lastTime = lastFlagLogTimes.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastTime < 500L) return;
        lastFlagLogTimes.put(player.getUniqueId(), now);

        Truthful.getInstance().getServerScheduler().runAsync(() -> {
            File baseFolder = new File(Truthful.getInstance().getPlugin().getDataFolder(), "recordings/flags/" + player.getName());
            if (!baseFolder.exists()) {
                baseFolder.mkdirs();
            }

            String dateStr = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            File flagFile = new File(baseFolder, dateStr + "_flags.jsonl");

            JsonObject json = new JsonObject();
            json.addProperty("timestamp", now);
            json.addProperty("player", player.getName());
            json.addProperty("uuid", player.getUniqueId().toString());
            json.addProperty("check", checkName);
            json.addProperty("details", details);
            json.addProperty("buffer", buffer);
            json.addProperty("threshold", threshold);
            json.addProperty("ping", data.getPing());
            json.addProperty("clientBrand", data.getClientBrand());

            // Position & Rotation Context
            json.addProperty("x", data.getX());
            json.addProperty("y", data.getY());
            json.addProperty("z", data.getZ());
            json.addProperty("yaw", data.getYaw());
            json.addProperty("pitch", data.getPitch());
            json.addProperty("deltaXZ", data.getDeltaXZ());
            json.addProperty("deltaY", data.getDeltaY());
            json.addProperty("airTicks", data.getAirTicks());
            json.addProperty("groundTicks", data.getGroundTicks());
            json.addProperty("onGround", data.isOnGround());

            try (FileWriter writer = new FileWriter(flagFile, true)) {
                writer.write(json.toString() + "\n");
            } catch (IOException e) {
                Truthful.getInstance().getPlugin().getLogger().warning("Failed to record flag telemetry: " + e.getMessage());
            }
        });
    }
}
