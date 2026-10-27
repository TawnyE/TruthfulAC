// FILE PATH: .\src\main\java\ret\tawny\truthful\managers\DiscordManager.java

package ret.tawny.truthful.managers;

import org.bukkit.Bukkit;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.config.api.Configuration;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.network.DiscordWebhook;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DiscordManager {

    // Prevent spamming webhooks (Rate limits)
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 2000; // 2 seconds per player

    public void removePlayer(UUID uuid) {
        cooldowns.remove(uuid);
    }

    public void sendAlert(PlayerData data, Check check, String debug, int vl) {
        Configuration config = Truthful.getInstance().getConfiguration();

        if (!config.isDiscordEnabled()) return;
        if (vl < config.getDiscordMinVl()) return;

        UUID uuid = data.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();

        if (now - cooldowns.getOrDefault(uuid, 0L) < COOLDOWN_MS) {
            return;
        }
        cooldowns.put(uuid, now);

        // Build Data
        String webhookUrl = config.getDiscordWebhookUrl();
        String username = config.getDiscordUsername();
        String avatarUrl = config.getDiscordAvatarUrl();
        int color = config.getDiscordEmbedColor();
        String footer = config.getDiscordFooter();
        boolean showHead = config.isDiscordHeadEnabled();

        Truthful.getInstance().getServerScheduler().runAsync(() -> {
            try {
                DiscordWebhook webhook = new DiscordWebhook(webhookUrl);
                webhook.setUsername(username);
                if (avatarUrl != null && !avatarUrl.isEmpty()) {
                    webhook.setAvatarUrl(avatarUrl);
                }

                DiscordWebhook.EmbedObject embed = new DiscordWebhook.EmbedObject();
                embed.setTitle(String.format("%s detected!", data.getPlayer().getName()));
                // Discord doesn't render '&' color codes, strip them from tags.
                String discordCheckName = check.getTaggedName(config).replaceAll("&[0-9a-fA-Fk-oK-OrR]", "");
                embed.setDescription("**Check:** " + discordCheckName + "\n**Info:** `" + debug + "`");
                embed.setColor(color);
                embed.setFooter(footer);

                // Fields
                embed.addField("Violations", String.valueOf(vl), true);
                embed.addField("Ping", data.getPing() + "ms", true);
                embed.addField("TPS", String.format("%.2f", Truthful.getInstance().getTps()), true);

                // Add client brand if known
                if (!data.getClientBrand().equals("Unknown")) {
                    embed.addField("Client", data.getClientBrand(), true);
                }

                if (showHead) {
                    // Use Crafatar for reliable 3D renders
                    embed.setThumbnail("https://crafatar.com/avatars/" + uuid + "?overlay");
                }

                webhook.addEmbed(embed);
                webhook.execute();

            } catch (Exception e) {
                Bukkit.getLogger().warning("[Truthful] Failed to send Discord webhook: " + e.getMessage());
            }
        });
    }
}