/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.modules;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class FoliaSupport {
    public static Boolean isFoliaServer = false;
    private enum TaskType {BUKKIT, FOLIA, THREADPOOL}

    private static final AtomicLong nextTaskId = new AtomicLong(1);
    private static long nextId() { return nextTaskId.getAndIncrement(); }
    private record TaskEntry(Object task, TaskType type) {}
    private static final Map<Long, TaskEntry> tasks = new ConcurrentHashMap<>();
    private static ExecutorService threadPool;
    private static BukkitScheduler bukkitScheduler;
    private static GlobalRegionScheduler foliaScheduler;
    private static AsyncScheduler foliaAsyncScheduler;
    private static JavaPlugin plugin;
    private static final long MS_PER_TICK = 50L;
    private record WrappedTask(long id, Runnable runnable) { }

    public static void init() {
        threadPool = Executors.newCachedThreadPool();
        sharedClass.TaskThreadPool = threadPool;
        plugin = sharedClass.plugin;
        doFoliaCheck();

        if (isFoliaServer) {
            foliaScheduler = Bukkit.getGlobalRegionScheduler();
            foliaAsyncScheduler = Bukkit.getAsyncScheduler();
        } else {
            bukkitScheduler = Bukkit.getScheduler();
        }
    }

    public static void doFoliaCheck() {
        if (!isFoliaServer) {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                isFoliaServer = true;
            } catch (ClassNotFoundException e) {
                isFoliaServer = false;
            }
        }
    }

    private static WrappedTask selfCleaning(Runnable fn) {
        long id = nextId();
        Runnable wrapped = () -> {
            try {
                fn.run();
            } finally {
                tasks.remove(id);
            }
        };
        return new WrappedTask(id, wrapped);
    }

    public static long ScheduleTask(JavaPlugin plugin, Runnable function, long delay) {
        WrappedTask wrappedTask = selfCleaning(function);
        Runnable wrappedRunnable = wrappedTask.runnable;

        if (isFoliaServer) {
            return addTask(foliaAsyncScheduler.runDelayed(
                    plugin,
                    t -> wrappedRunnable.run(),
                    Math.max(delay, 1L) * MS_PER_TICK,
                    TimeUnit.MILLISECONDS
            ), TaskType.FOLIA, wrappedTask.id);
        } else {
            return addTask(
                    bukkitScheduler.runTaskLater(plugin, wrappedRunnable, delay),
                    TaskType.BUKKIT,
                    wrappedTask.id
            );
        }
    }

    public static long runEntityTask(JavaPlugin plugin, Entity entity, Runnable function) {
        WrappedTask wrappedTask = selfCleaning(function);
        Runnable wrappedRunnable = wrappedTask.runnable;

        if (isFoliaServer) {
            return addTask(entity.getScheduler().run(
                    plugin,
                    t -> wrappedRunnable.run(),
                    null // retired callback - no-op if the entity is removed before running
            ), TaskType.FOLIA, wrappedTask.id);
        } else {
            // Bukkit: run on the main thread
            return addTask(
                    bukkitScheduler.runTask(plugin, wrappedRunnable),
                    TaskType.BUKKIT,
                    wrappedTask.id
            );
        }
    }

    public static long DelayTask(Runnable function, long delay) {
        WrappedTask wrappedTask = selfCleaning(function);
        Runnable wrappedRunnable = wrappedTask.runnable;
        return addTask(threadPool.submit(() -> {
            try {
                Thread.sleep(Math.max(delay, 1L) * MS_PER_TICK);
                wrappedRunnable.run();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                tasks.remove(wrappedTask.id);
            }
        }), TaskType.THREADPOOL, wrappedTask.id);
    }

    // Asynchronous task
    public static long runTask(Runnable function) {
        WrappedTask wrappedTask = selfCleaning(function);
        return addTask(threadPool.submit(wrappedTask.runnable), TaskType.THREADPOOL, wrappedTask.id);
    }

    //TODO: Deprecate
    public static long runThreadPoolTask(Runnable function) {
        WrappedTask wrappedTask = selfCleaning(function);
        return addTask(threadPool.submit(wrappedTask.runnable), TaskType.THREADPOOL, wrappedTask.id);
    }

    public static long runTaskSynchronously(Runnable function) {
        WrappedTask wrappedTask = selfCleaning(function);
        Runnable wrappedRunnable = wrappedTask.runnable;

        if (isFoliaServer) {
            return addTask(
                    foliaScheduler.run(plugin, t -> wrappedRunnable.run()),
                    TaskType.FOLIA,
                    wrappedTask.id
            );
        } else {
            return addTask(
                    bukkitScheduler.runTask(plugin, wrappedRunnable),
                    TaskType.BUKKIT,
                    wrappedTask.id
            );
        }
    }

    public static void runTasklessSynchronously(JavaPlugin plugin, Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        runTaskSynchronously(task);
    }

    public static long ScheduleRepeatingTask(JavaPlugin plugin, Runnable function, long delay, long period) {
        Object task;
        if (isFoliaServer) {
            long safeDelay = Math.max(delay, 1L);
            long safePeriod = Math.max(period, 1L);
            task = foliaAsyncScheduler.runAtFixedRate(
                    plugin,
                    t -> function.run(),
                    safeDelay * MS_PER_TICK,
                    safePeriod * MS_PER_TICK,
                    TimeUnit.MILLISECONDS
            );
            return addTask(task, TaskType.FOLIA);
        } else {
            task = bukkitScheduler.runTaskTimer(plugin, function, delay, period);
            return addTask(task, TaskType.BUKKIT);
        }
    }

    public static boolean CancelTask(Long taskId) {
        TaskEntry entry = tasks.remove(taskId);
        if (entry == null) return false;

        Object task = entry.task;
        TaskType type = entry.type;

        try {
            switch (type) {
                case FOLIA:
                    ((ScheduledTask) task).cancel();
                    break;
                case BUKKIT:
                    ((BukkitTask) task).cancel();
                    break;
                case THREADPOOL:
                    ((Future<?>) task).cancel(true);
                    break;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long addTask(Object task, TaskType type, long taskId) {
        tasks.put(taskId, new TaskEntry(task, type));
        return taskId;
    }

    private static long addTask(Object task, TaskType type) {
        long taskId = nextId();
        tasks.put(taskId, new TaskEntry(task, type));
        return taskId;
    }
}