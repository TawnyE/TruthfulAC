package ret.tawny.truthful.utils.world;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public final class BlockWriteDedup {

    /**
     * A single world's dedup window. Writers only ever touch {@link #current};
     * the rotating task swaps it with {@link #next} and clears what became stale.
     */
    private static final class Buffer {
        volatile Set<BlockKey> current = ConcurrentHashMap.newKeySet();
        Set<BlockKey> next = ConcurrentHashMap.newKeySet();
    }

    /** Identical (coordinate, blockId) within one tick is a duplicate rebroadcast. */
    public record BlockKey(int x, int y, int z, int blockId) {}

    private static final Map<UUID, Buffer> BUFFERS = new ConcurrentHashMap<>();

    private BlockWriteDedup() {}

    /**
     * Claims the write for this tick. Returns true when this (coordinate, blockId)
     * is the first of its tick and must be applied to the cache; false when an
     * identical write from another player's packet stream was already applied.
     */
    public static boolean tryClaim(UUID worldUid, int x, int y, int z, int blockId) {
        return BUFFERS.computeIfAbsent(worldUid, k -> new Buffer())
                .current.add(new BlockKey(x, y, z, blockId));
    }

    /**
     * Advances every world's window by one tick. Called from the global scheduler
     * once per tick. A writer that captured the old window reference just adds a
     * value into the set being drained: the write is already applied and its dedup
     * entry is simply lost — never the other way around, so no legitimate write
     * can ever be skipped because of a rotation race.
     */
    public static void rotateAll() {
        for (Buffer buffer : BUFFERS.values()) {
            Set<BlockKey> expired = buffer.current;
            buffer.current = buffer.next;
            buffer.next = expired;
            buffer.next.clear();
        }
    }

    /** Drops the dedup state of a world (last player left); the next write re-creates it. */
    public static void removeWorld(UUID worldUid) {
        BUFFERS.remove(worldUid);
    }
}