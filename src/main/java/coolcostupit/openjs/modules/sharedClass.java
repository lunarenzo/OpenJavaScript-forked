package coolcostupit.openjs.modules;

import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.utility.configurationUtil;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

public class sharedClass {
    public static Boolean IsPapiLoaded;
    public static PluginDescriptionFile PluginDescription;
    public static configurationUtil configUtil;
    public static pluginLogger logger;
    public static JavaPlugin plugin;
}
