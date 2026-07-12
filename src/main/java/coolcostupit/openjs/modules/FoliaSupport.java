/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.modules;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FoliaSupport {
    public static Boolean isFoliaServer = false;
    private enum TaskType {BUKKIT, FOLIA, THREADPOOL}

    private static final AtomicInteger nextTaskId = new AtomicInteger(1);
    private static int nextId() { return nextTaskId.getAndIncrement(); }
    private record TaskEntry(Object task, TaskType type) {}
    private static final Map<Integer, TaskEntry> tasks = new ConcurrentHashMap<>();
    private static ExecutorService threadPool;
    private static BukkitScheduler bukkitScheduler;
    private static GlobalRegionScheduler foliaScheduler;
    private static JavaPlugin plugin;
    private static final long MS_PER_TICK = 50L;

    @FunctionalInterface
    private interface SyncScheduler { int run(Runnable fn); }
    private static SyncScheduler syncScheduler;
    private record WrappedTask(int id, Runnable runnable) { }

    // TODO: Revert to running the if statement within the methods (bytecode does optimize it properly rather than doing a method stacking call)
    public static void init() {
        threadPool = Executors.newCachedThreadPool();
        sharedClass.TaskThreadPool = threadPool;
        plugin = sharedClass.plugin;
        doFoliaCheck();

        if (isFoliaServer) {
            foliaScheduler = Bukkit.getGlobalRegionScheduler();
            syncScheduler = (fn) -> {
                int id = nextId();
                ScheduledTask task = foliaScheduler.run(plugin, t -> {
                    try {
                        fn.run();
                    } finally {
                        tasks.remove(id);
                    }
                });
                tasks.put(id, new TaskEntry(task, TaskType.FOLIA));
                return id;
            };
        } else {
            bukkitScheduler = Bukkit.getScheduler();
            syncScheduler = (fn) -> {
                int id = nextId();
                BukkitTask task = bukkitScheduler.runTask(plugin, () -> {
                    try {
                        fn.run();
                    } finally {
                        tasks.remove(id);
                    }
                });
                tasks.put(id, new TaskEntry(task, TaskType.BUKKIT));
                return id;
            };
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
        int id = nextId();
        Runnable wrapped = () -> {
            try {
                fn.run();
            } finally {
                tasks.remove(id);
            }
        };
        return new WrappedTask(id, wrapped);
    }

    public static int ScheduleTask(JavaPlugin plugin, Runnable function, long delay) {
        Object task;
        if (isFoliaServer) {
            WrappedTask wrappedTask = selfCleaning(function);
            Runnable wrappedRunnable = wrappedTask.runnable;

            return addTask(Bukkit.getAsyncScheduler().runDelayed(
                    plugin,
                    t -> wrappedRunnable.run(),
                    Math.max(delay, 1L) * MS_PER_TICK,
                    TimeUnit.MILLISECONDS
            ), TaskType.FOLIA, wrappedTask.id);
        } else {
            task = Bukkit.getScheduler().runTaskLater(plugin, function, delay);
            return addTask(task, TaskType.BUKKIT);
        }
    }

    public static int runEntityTask(JavaPlugin plugin, Entity entity, Runnable function) {
        if (isFoliaServer) {
            WrappedTask wrappedTask = selfCleaning(function);
            Runnable wrappedRunnable = wrappedTask.runnable;
            return addTask(entity.getScheduler().run(
                    plugin,
                    t -> wrappedRunnable.run(),
                    null // retired callback - no-op if the entity is removed before running
            ), TaskType.FOLIA, wrappedTask.id);
        } else {
            // Bukkit: just run Runnable on main thread
            BukkitTask task = Bukkit.getScheduler().runTask(plugin, function);
            return addTask(task, TaskType.BUKKIT);
        }
    }

    public static int DelayTask(Runnable function, long delay) {
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
    public static int runTask(Runnable function) {
        WrappedTask wrappedTask = selfCleaning(function);
        return addTask(threadPool.submit(wrappedTask.runnable), TaskType.THREADPOOL, wrappedTask.id);
    }

    //TODO: Deprecate
    public static int runThreadPoolTask(Runnable function) {
        WrappedTask wrappedTask = selfCleaning(function);
        return addTask(threadPool.submit(wrappedTask.runnable), TaskType.THREADPOOL, wrappedTask.id);
    }

    public static int runTaskSynchronously(Runnable function) {
        return syncScheduler.run(function);
    }

    public static void runTasklessSynchronously(JavaPlugin plugin, Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        runTaskSynchronously(task);
    }

    public static int ScheduleRepeatingTask(JavaPlugin plugin, Runnable function, long delay, long period) {
        Object task;
        if (isFoliaServer) {
            long safeDelay = Math.max(delay, 1L);
            long safePeriod = Math.max(period, 1L);
            task = Bukkit.getAsyncScheduler().runAtFixedRate(
                    plugin,
                    t -> function.run(),
                    safeDelay * MS_PER_TICK,
                    safePeriod * MS_PER_TICK,
                    TimeUnit.MILLISECONDS
            );
            return addTask(task, TaskType.FOLIA);
        } else {
            task = Bukkit.getScheduler().runTaskTimer(plugin, function, delay, period);
            return addTask(task, TaskType.BUKKIT);
        }
    }

    public static boolean CancelTask(int taskId) {
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

    private static int addTask(Object task, TaskType type, int taskId) {
        tasks.put(taskId, new TaskEntry(task, type));
        return taskId;
    }

    private static int addTask(Object task, TaskType type) {
        int taskId = nextId();
        tasks.put(taskId, new TaskEntry(task, type));
        return taskId;
    }
}