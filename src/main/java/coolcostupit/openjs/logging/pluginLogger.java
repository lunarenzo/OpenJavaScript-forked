package coolcostupit.openjs.logging;

import coolcostupit.openjs.utility.chatColors;
import coolcostupit.openjs.utility.configurationUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

public class pluginLogger {

    public static final String RESET = "\u001B[0m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[38;5;49m";
    public static final String BLUE = "\u001B[34m";
    public static final String LIGHT_BLUE = "\u001B[38;5;81m";
    public static final String ORANGE = "\u001B[38;5;214m";
    private final configurationUtil configUtil;
    private final Logger logger;

    public pluginLogger(JavaPlugin plugin, configurationUtil configUtil) {
        this.logger = plugin.getLogger();
        this.configUtil = configUtil;
    }

    public void log(Level level, String message, String colorCode) {
        logger.log(level, colorCode + message + RESET);
        if (configUtil.getConfigFromBuffer("BroadcastToOps", true) && level == Level.SEVERE || level == Level.WARNING) {
            OpsLogger.LogToOps(Collections.singletonList(chatColors.RED + message));
        }
    }
}
