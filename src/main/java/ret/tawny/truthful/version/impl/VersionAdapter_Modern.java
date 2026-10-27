package ret.tawny.truthful.version.impl;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import ret.tawny.truthful.version.IVersionAdapter;
import ret.tawny.truthful.version.ServerVersion;

public final class VersionAdapter_Modern implements IVersionAdapter {

    private final ServerVersion version;

    public VersionAdapter_Modern(final ServerVersion version) {
        this.version = version;
    }

    @Override
    public double getEntityInteractionRange(Player player) {
        double range = ret.tawny.truthful.data.PlayerData.getAttributeValue(player, "PLAYER_ENTITY_INTERACTION_RANGE", -1.0D);
        if (range > 0.0D) return range;
        return player.getGameMode() == GameMode.CREATIVE ? 5.0 : 3.0;
    }

    @Override
    public double getBlockInteractionRange(Player player) {
        double range = ret.tawny.truthful.data.PlayerData.getAttributeValue(player, "PLAYER_BLOCK_INTERACTION_RANGE", -1.0D);
        if (range > 0.0D) return range;
        return player.getGameMode() == GameMode.CREATIVE ? 5.0 : 4.5;
    }

    @Override
    public double getBedBounceRestitution() {
        // 26.2 "Chaos Cubed": beds bounce entities at 75% of impact velocity
        // instead of 66%, matching Bedrock Edition.
        return version.isAtLeast(ServerVersion.V26_2) ? 0.75D : (2.0D / 3.0D);
    }

    @Override
    public boolean supportsMovementModifierAttributes() {
        return version.isAtLeast(ServerVersion.V26_2);
    }

    @Override
    public boolean supportsGeysers() {
        return version.isAtLeast(ServerVersion.V26_2);
    }

    @Override
    public boolean supportsWildernessBoundBlocks() {
        return version.isAtLeast(ServerVersion.V26_3);
    }

    @Override
    public boolean supportsClientTickEnd() {
        return version.isAtLeast(ServerVersion.V1_21_2);
    }

    @Override
    public boolean supportsModernInputTransform() {
        return version.isAtLeast(ServerVersion.V1_21_5);
    }

    @Override
    public ServerVersion getVersion() {
        return this.version;
    }
}
