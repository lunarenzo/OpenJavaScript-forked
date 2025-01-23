package coolcostupit.openjs.modules;

import com.tcoded.folialib.FoliaLib;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

public class FoliaSupport {
    private static Boolean cached = null;
    private static Boolean isFoliaChecked = null;

    //Check if the server is running on Folia and cache the result
    public static boolean isFolia() {
        if (cached == null) {
            if (isFoliaChecked == null) {
                boolean foliaDetected = false;
                try {
                    // Check if Folia-specific methods exist
                    Method getRegionScheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler");
                    foliaDetected = getRegionScheduler != null;
                } catch (NoSuchMethodException | SecurityException ignored) {
                    // Folia-specific methods not found, assume non-Folia server
                }
                isFoliaChecked = foliaDetected;
            }
            cached = true;
        }
        return isFoliaChecked;
    }

    public static void ScheduleTask(JavaPlugin plugin, Runnable function, long delay) {
        if (isFolia()) {
            // Use FoliaScheduler
            //FoliaAsyncScheduler foliaScheduler = new FoliaAsyncScheduler(plugin);
            //foliaScheduler.runDelayed(function, delay);
            FoliaLib foliaLib = new FoliaLib(plugin);
            //foliaLib.getScheduler().run
        } else {
            // Use normal Bukkit scheduler
            Bukkit.getScheduler().runTaskLater(plugin, function, delay);
        }
    }

    public static void ScheduleRepeatingTask(JavaPlugin plugin, Runnable function, long delay, long period) {
        if (isFolia()) {
            // Use FoliaScheduler
            //FoliaAsyncScheduler foliaScheduler = new FoliaAsyncScheduler(plugin);
            //foliaScheduler.runAtFixedRate(function, delay, period);
            FoliaLib foliaLib = new FoliaLib(plugin);
            foliaLib.getScheduler().runTimer(function, delay, period);
        } else {
            // Use normal Bukkit scheduler
            Bukkit.getScheduler().runTaskTimer(plugin, function, delay, period);
        }
    }
}
