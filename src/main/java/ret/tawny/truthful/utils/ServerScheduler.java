package ret.tawny.truthful.utils;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerScheduler {

    private final FoliaLib foliaLib;

    /*
     * Dedicated, single-threaded executor for world-cache rebuild / block-change
     * application. Kept strictly separate from the FoliaLib async pool so a FAWE
     * rebuild storm cannot starve (or be starved by) Discord alerting, flag logging,
     * or telemetry writes — those keep their own scheduling path.
     */
    private final ExecutorService worldCacheExecutor;

    public ServerScheduler(Plugin plugin) {
        this.foliaLib = new FoliaLib(plugin);
        this.worldCacheExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "Truthful-WorldCache-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * Offload world-cache rebuild/update work onto the dedicated executor so it never
     * blocks the packet-send path and never contends with Discord/flag-logging async work.
     */
    public void runWorldCache(Runnable runnable) {
        ExecutorService cache = this.worldCacheExecutor;
        if (cache == null || cache.isShutdown()) {
            // Executor already torn down (plugin disabling); drop the work safely.
            return;
        }
        cache.execute(runnable);
    }

    public void shutdownWorldCache() {
        ExecutorService cache = this.worldCacheExecutor;
        if (cache != null) {
            cache.shutdown();
        }
    }

    public FoliaLib getFoliaLib() {
        return foliaLib;
    }

    public void runRegion(Entity entity, Runnable runnable) {
        foliaLib.getImpl().runAtEntity(entity, task -> runnable.run());
    }

    public WrappedTask runRegionLater(Entity entity, Runnable runnable, long delayTicks) {
        return foliaLib.getImpl().runAtEntityLater(entity, runnable, delayTicks);
    }

    public WrappedTask runRegionTimer(Entity entity, Runnable runnable, long delayTicks, long periodTicks) {
        return foliaLib.getImpl().runAtEntityTimer(entity, runnable, delayTicks, periodTicks);
    }

    public void runGlobal(Runnable runnable) {
        foliaLib.getImpl().runNextTick(task -> runnable.run());
    }

    public WrappedTask runGlobalLater(Runnable runnable, long delayTicks) {
        return foliaLib.getImpl().runLater(runnable, delayTicks);
    }

    public WrappedTask runGlobalTimer(Runnable runnable, long delayTicks, long periodTicks) {
        return foliaLib.getImpl().runTimer(runnable, delayTicks, periodTicks);
    }

    public void runAsync(Runnable runnable) {
        foliaLib.getImpl().runAsync(task -> runnable.run());
    }

    public WrappedTask runAsyncLater(Runnable runnable, long delayTicks) {
        return foliaLib.getImpl().runLaterAsync(runnable, delayTicks);
    }

    public WrappedTask runAsyncTimer(Runnable runnable, long delayTicks, long periodTicks) {
        return foliaLib.getImpl().runTimerAsync(runnable, delayTicks, periodTicks);
    }

    public void cancelAll() {
        foliaLib.getImpl().cancelAllTasks();
    }
}
