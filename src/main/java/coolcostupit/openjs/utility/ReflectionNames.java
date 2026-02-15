package coolcostupit.openjs.utility;

import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import org.bukkit.Bukkit;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ReflectionNames {

    public static String CONTAINER_MENU_FIELD;
    public static String CONTAINER_ID_FIELD;
    public static String GET_TYPE_METHOD;

    public static Method getHandleMethod;
    public static Field activeContainerField;
    public static Field containerIdField;
    public static Method getTypeMethod;

    public static ProtocolManager protocolManager;
    public static boolean protocolLibAvailable;

    public static void initialize() {
        try {
            String version = Bukkit.getServer().getMinecraftVersion();
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[1]);
            int minor = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

            // Mapping names based on Version
            // 1.20.5+ moved to "containerMenu" and uses modern mappings
            if (major > 20 || (major == 20 && minor >= 5)) {
                CONTAINER_MENU_FIELD = "containerMenu";
                CONTAINER_ID_FIELD = "containerId";
                GET_TYPE_METHOD = "getType";
            } else {
                // Older versions (1.17 - 1.20.4)
                CONTAINER_MENU_FIELD = "activeContainer";
                CONTAINER_ID_FIELD = "containerId";
                GET_TYPE_METHOD = "getType";
            }

            // Pre-cache the Reflection objects
            Class<?> craftPlayerClass = Class.forName(Bukkit.getServer().getClass().getPackage().getName() + ".entity.CraftPlayer");
            getHandleMethod = craftPlayerClass.getMethod("getHandle");

            Class<?> nmsPlayerClass = getHandleMethod.getReturnType();
            activeContainerField = nmsPlayerClass.getField(CONTAINER_MENU_FIELD);

            Class<?> containerClass = activeContainerField.getType();
            containerIdField = containerClass.getField(CONTAINER_ID_FIELD);
            getTypeMethod = containerClass.getMethod(GET_TYPE_METHOD);

            protocolManager = com.comphenix.protocol.ProtocolLibrary.getProtocolManager();
            protocolLibAvailable = Bukkit.getPluginManager().isPluginEnabled("ProtocolLib");
        } catch (Exception e) {
            Bukkit.getLogger().severe("[OpenJS] Failed to initialize ReflectionNames: " + e.getMessage());
        }
    }
}