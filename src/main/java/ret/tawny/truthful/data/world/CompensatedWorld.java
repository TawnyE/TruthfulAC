package ret.tawny.truthful.data.world;

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import ret.tawny.truthful.utils.world.ChunkSnapshot;
import ret.tawny.truthful.Truthful;
import java.util.UUID;

/**
 * CompensatedWorld - Manages the local block cache using Global IDs.
 * Thread-safe access for async checks to prevent main-thread deadlocks or crashes.
 */
public final class CompensatedWorld {

    // FIXED: Removed 'final' so we can update it when players change worlds (Multiverse)
    private UUID worldUid;

    // Per-player chunk cache: the old single-entry cache hit the shared,
    // synchronized global LRU on EVERY cross-chunk read (block edges are
    // crossed constantly). A small per-player map keeps the player's whole
    // immediate neighborhood local; the cap stays far below the global
    // capacity so locally-held snapshots cannot outlive their global
    // counterparts and go stale.
    private static final int LOCAL_CHUNK_CAP = 16;
    private final java.util.concurrent.ConcurrentHashMap<Long, ChunkSnapshot> localChunks =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Throttle: refresh the global LRU position of the player's chunk at most this often.
    // Reads hit the local cache and therefore never touch the shared LRU map, which let the
    // player's own chunk be evicted first under chunk-streaming pressure (false UNLOADED_CHUNK).
    private static final long GLOBAL_TOUCH_INTERVAL_NANOS = 500_000_000L;
    private volatile long lastGlobalTouchNanos;

    // Per-player latency view (Grim-style): block edits are recorded as
    // "stale" (the OLD state) at packet-send time and only revealed once the
    // client ACKs the bracketing transaction - the engine always simulates
    // the world the player actually sees, not the world the server sent.
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> staleView = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Short, long[]> pendingFlushes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Short, Long> flushRegisterTimes = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int STALE_VIEW_CAP = 8192;
    private static final long FLUSH_TTL_MILLIS = 10_000L;

    public void recordStaleView(long packedPos, int oldGlobalId) {
        if (staleView.size() >= STALE_VIEW_CAP) return; // mega-edits degrade to immediate-apply
        staleView.putIfAbsent(packedPos, oldGlobalId);
    }

    public void registerFlush(short transactionId, long[] packedPositions) {
        long now = System.currentTimeMillis();
        // Drop un-acked batches older than the TTL (withheld-ack safety) -
        // AND clear their pins, otherwise those blocks stay frozen as their
        // pre-change state in this player's view forever (ground detection
        // goes blind in that area: below=AIR while standing on the block).
        flushRegisterTimes.forEach((id, t) -> {
            if (now - t > FLUSH_TTL_MILLIS) {
                long[] expired = pendingFlushes.remove(id);
                flushRegisterTimes.remove(id);
                if (expired != null) {
                    for (long packed : expired) {
                        staleView.remove(packed);
                    }
                }
            }
        });
        pendingFlushes.put(transactionId, packedPositions);
        flushRegisterTimes.put(transactionId, now);
    }

    /** Called when the client acks a transaction: reveal the new blocks it has processed. */
    public void onTransactionAck(short id) {
        long[] positions = pendingFlushes.remove(id);
        flushRegisterTimes.remove(id);
        if (positions == null) return;
        for (long packed : positions) {
            staleView.remove(packed);
        }
    }

    private static long packPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }

    public CompensatedWorld(UUID worldUid) {
        this.worldUid = worldUid;
    }

    public int getSharedBlockId(int x, int y, int z) {
        ChunkSnapshot chunk = chunkFor(x >> 4, z >> 4);
        return (chunk == null) ? 0 : chunk.getBlockId(x, y, z);
    }

    /** Local-cache-aware chunk lookup; falls back to the shared global cache. */
    private ChunkSnapshot chunkFor(int chunkX, int chunkZ) {
        long key = getChunkKey(chunkX, chunkZ);
        ChunkSnapshot chunk = localChunks.get(key);
        if (chunk != null) return chunk;

        chunk = Truthful.getInstance().getGlobalWorldCache().getChunk(worldUid, chunkX, chunkZ);
        if (chunk != null) {
            if (localChunks.size() >= LOCAL_CHUNK_CAP) localChunks.clear();
            localChunks.put(key, chunk);
            maybeTouchGlobal(key);
        }
        return chunk;
    }

    // FIXED: Allows the PlayerData to re-bind the cache to the new world
    public void setWorldUid(UUID worldUid) {
        this.worldUid = worldUid;
        this.clear();
    }

    public UUID getWorldUid() {
        return this.worldUid;
    }

    public void addChunk(int x, int z, ChunkSnapshot snapshot) {
        Truthful.getInstance().getGlobalWorldCache().addChunk(worldUid, x, z, snapshot);
        localChunks.put(getChunkKey(x, z), snapshot);
    }

    public void removeChunk(int x, int z) {
        localChunks.remove(getChunkKey(x, z));
    }

    public void updateBlock(int x, int y, int z, int globalId) {
        Truthful.getInstance().getGlobalWorldCache().updateBlock(worldUid, x, y, z, globalId);
    }

    /**
     * Gets the raw global block ID at a location 100% Async.
     * Returns 0 (AIR) if chunk is not loaded/cached.
     * Use this instead of getBlockState() when you only need the raw ID
     * for property lookups (avoids WrappedBlockState allocation).
     */
    public int getBlockId(int x, int y, int z) {
        // Latency view first: while this player has not yet acked a block
        // change, they still see the OLD state.
        if (!staleView.isEmpty()) {
            Integer stale = staleView.get(packPos(x, y, z));
            if (stale != null) return stale;
        }

        ChunkSnapshot chunk = chunkFor(x >> 4, z >> 4);
        return (chunk == null) ? 0 : chunk.getBlockId(x, y, z);
    }

    private void maybeTouchGlobal(long key) {
        long now = System.nanoTime();
        if (now - lastGlobalTouchNanos < GLOBAL_TOUCH_INTERVAL_NANOS) return;
        lastGlobalTouchNanos = now;
        Truthful.getInstance().getGlobalWorldCache()
                .touchChunk(worldUid, (int) (key >> 32), (int) key);
    }

    /**
     * Gets the WrappedBlockState at a location 100% Async.
     * Returns AIR (0) if chunk is not loaded/cached.
     * Use getBlockId() instead when you only need the raw ID for property lookups.
     */
    public WrappedBlockState getBlockState(int x, int y, int z) {
        return WrappedBlockState.getByGlobalId(getBlockId(x, y, z));
    }

    /**
     * Checks if a chunk is currently cached.
     */
    public boolean isChunkLoaded(int x, int z) {
        ChunkSnapshot local = localChunks.get(getChunkKey(x, z));
        if (local != null) return true;
        return Truthful.getInstance().getGlobalWorldCache().getChunk(worldUid, x, z) != null;
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public void clear() {
        localChunks.clear();
        staleView.clear();
        pendingFlushes.clear();
        flushRegisterTimes.clear();
    }
}