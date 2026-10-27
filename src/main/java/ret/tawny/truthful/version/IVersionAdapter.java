package ret.tawny.truthful.version;

import org.bukkit.entity.Player;

/**
 * Version-gated behaviour. Capabilities are expressed as semantic questions,
 * never raw version numbers, so callers stay readable and new versions only
 * touch this interface.
 */
public interface IVersionAdapter {

    /** Player -> entity attack reach, attribute-aware (1.20.5+), else 3.0/5.0. */
    double getEntityInteractionRange(Player player);

    /** Player -> block interaction reach, attribute-aware, else 4.5/5.0. */
    double getBlockInteractionRange(Player player);

    /**
     * Restitution applied when an entity lands on a bed-class bouncy block.
     * 26.2 raised this from 2/3 to 3/4 to match Bedrock.
     */
    double getBedBounceRestitution();

    /** 26.2+ added air_drag_modifier / bounciness / friction_modifier attributes. */
    boolean supportsMovementModifierAttributes();

    /** 26.2+ added potent sulfur geysers (block-driven vertical impulses). */
    boolean supportsGeysers();

    /** 26.3+ added bouncy shelf mushrooms, straw beds and cushion entities. */
    boolean supportsWildernessBoundBlocks();

    /** 1.21.2+ clients send CLIENT_TICK_END and known inputs per tick. */
    boolean supportsClientTickEnd();

    /** 1.21.5+ uses the modern square-movement input transform. */
    boolean supportsModernInputTransform();

    ServerVersion getVersion();
}
