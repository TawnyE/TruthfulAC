package ret.tawny.truthful.data.world;

import org.bukkit.Bukkit;
import ret.tawny.truthful.utils.world.ChunkSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GlobalWorldCache - Shared block data across all players to save memory and CPU.
 * Uses LRU eviction per world to prevent unbounded memory growth.
 */
public final class GlobalWorldCache {

    /**
     * Per-world capacity floor. The real capacity is view-distance-aware so that a
     * player's ENTIRE rendered area fits: with the old flat cap of 256, a player on a
     * view-distance-10 world (441 rendered chunks) had their own chunk LRU-evicted,
     * which made ground detection fail and produced UNLOADED_CHUNK false flags.
     */
    private static final int MIN_CHUNKS_PER_WORLD = 256;

    private final Map<UUID, Map<Long, ChunkSnapshot>> worldCaches = new java.util.concurrent.ConcurrentHashMap<>();

    private static int capacityForWorld() {
        int view = Math.max(2, Bukkit.getViewDistance());
        int rendered = (2 * view + 1) * (2 * view + 1);
        // +128 slack for chunks streaming in ahead of the player while moving
        return Math.max(MIN_CHUNKS_PER_WORLD, rendered + 128);
    }

    public void addChunk(UUID worldUid, int x, int z, ChunkSnapshot snapshot) {
        int capacity = capacityForWorld();
        Map<Long, ChunkSnapshot> cache = worldCaches.computeIfAbsent(worldUid, k -> 
            new LinkedHashMap<Long, ChunkSnapshot>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, ChunkSnapshot> eldest) {
                    return size() > capacity;
                }
            }
        );
        synchronized (cache) {
            cache.put(getChunkKey(x, z), snapshot);
        }
    }

    public void removeChunk(UUID worldUid, int x, int z) {
        Map<Long, ChunkSnapshot> cache = worldCaches.get(worldUid);
        if (cache != null) {
            synchronized (cache) {
                cache.remove(getChunkKey(x, z));
                if (cache.isEmpty()) worldCaches.remove(worldUid);
            }
        }
    }

    public void updateBlock(UUID worldUid, int x, int y, int z, int globalId) {
        ChunkSnapshot chunk = getChunk(worldUid, x >> 4, z >> 4);
        if (chunk != null) {
            chunk.setBlock(x, y, z, globalId);
        }
    }

    public ChunkSnapshot getChunk(UUID worldUid, int x, int z) {
        Map<Long, ChunkSnapshot> cache = worldCaches.get(worldUid);
        if (cache == null) return null;
        synchronized (cache) {
            return cache.get(getChunkKey(x, z));
        }
    }

    /**
     * Refreshes the LRU position of a chunk without returning its data.
     * Used by per-player caches whose hot-path reads bypass this map, so the
     * player's own chunk cannot be silently evicted by unrelated chunk traffic.
     */
    public void touchChunk(UUID worldUid, int x, int z) {
        Map<Long, ChunkSnapshot> cache = worldCaches.get(worldUid);
        if (cache == null) return;
        synchronized (cache) {
            cache.get(getChunkKey(x, z));
        }
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public void clearWorld(UUID worldUid) {
        worldCaches.remove(worldUid);
    }

    public void clearAll() {
        worldCaches.clear();
    }
}
