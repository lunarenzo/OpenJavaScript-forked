package coolcostupit.openjs.utility;

import com.comphenix.protocol.ProtocolManager;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.sharedClass;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;

// All NMS and Reflection goes here
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
    public static boolean dialogApiSupported = false;

    public static Class<?> dialogClass;
    public static Class<?> dialogBaseClass;
    public static Class<?> dialogTypeClass;
    public static Class<?> actionButtonClass;
    public static Class<?> dialogBodyClass;
    public static Class<?> dialogInputClass;
    public static Class<?> dialogActionClass;
    public static Class<?> createFunctionalInterface;
    public static Class<?> dialogAfterActionClass;
    public static Object dialogAfterActionNone;
    public static Object cachedClickOptions;
    public static Method createMethod;
    public static Method customClickMethod;
    public static Method plainMessage;
    public static Method textInputMethod;
    public static Method baseBuilderMethod;
    public static Method builderMethod;
    public static Method confirmation;
    public static Method numberRangeMethod;
    public static Method notice;
    public static Method multiAction;
    public static Method multiActionWithColumns;
    public static Method multiActionBuild;
    public static Method multiActionColumns;
    public static Method multiActionExitAction;


    public static void initialize() {
        try {
            dialogClass = Class.forName("io.papermc.paper.dialog.Dialog");
            dialogBaseClass = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
            dialogTypeClass = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
            actionButtonClass = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton");
            dialogBodyClass = Class.forName("io.papermc.paper.registry.data.dialog.body.DialogBody");
            dialogInputClass = Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput");
            dialogActionClass = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction");
            dialogAfterActionClass = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase$DialogAfterAction");
            plainMessage = dialogBodyClass.getMethod("plainMessage", Component.class, int.class);
            textInputMethod = dialogInputClass.getMethod("text", String.class, Component.class);
            baseBuilderMethod = dialogBaseClass.getMethod("builder", Component.class);
            builderMethod = actionButtonClass.getMethod("builder", Component.class);
            confirmation = dialogTypeClass.getMethod("confirmation", actionButtonClass, actionButtonClass);
            notice = dialogTypeClass.getMethod("notice", actionButtonClass);

            for (Object constant : dialogAfterActionClass.getEnumConstants()) {
                if (constant.toString().equals("NONE")) {
                    dialogAfterActionNone = constant;
                    break;
                }
            }

            for (Method m : dialogTypeClass.getMethods()) {
                if (m.getName().equals("multiAction") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == List.class) {
                    multiAction = m;
                    break;
                }
            }

            Class<?> multiActionBuilderClass = multiAction.getReturnType();
            multiActionBuild     = multiActionBuilderClass.getMethod("build");
            multiActionColumns   = multiActionBuilderClass.getMethod("columns", int.class);
            multiActionExitAction = multiActionBuilderClass.getMethod("exitAction", actionButtonClass);

            for (Method m : dialogTypeClass.getMethods()) {
                if (m.getName().equals("multiAction") && m.getParameterCount() == 3) {
                    multiActionWithColumns = m;
                    break;
                }
            }

            sharedClass.logger.debug("multiAction isVarArgs: " + ReflectionNames.multiAction.isVarArgs());
            sharedClass.logger.debug("multiAction param type: " + ReflectionNames.multiAction.getParameterTypes()[0]);

            for (Method m : ReflectionNames.dialogInputClass.getMethods()) {
                if (!m.getName().equals("numberRange") || m.getParameterCount() != 4) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params[0] != String.class || !params[1].getName().endsWith("Component")) continue;
                if (params[2] == double.class) {
                    numberRangeMethod = m;
                    break;
                }
                numberRangeMethod = m;
            }

            for (Method m : dialogClass.getMethods()) {
                if (m.getName().equals("create") && m.getParameterCount() == 1) {
                    createMethod = m;
                    createFunctionalInterface = m.getParameterTypes()[0];
                    break;
                }
            }

            for (Method m : dialogActionClass.getMethods()) {
                if (!m.getName().equals("customClick")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 2 && params[0].getName().equals("io.papermc.paper.registry.data.dialog.action.DialogActionCallback")) {
                    customClickMethod = m;
                    break;
                }
            }

            if (customClickMethod != null) {
                Class<?> optionsType = customClickMethod.getParameterTypes()[1];
                Method optionsBuilderMethod = optionsType.getMethod("builder");
                Object optionsBuilder = optionsBuilderMethod.invoke(null);
                Class<?> optionsBuilderClass = optionsBuilderMethod.getReturnType();
                Method usesMethod = optionsBuilderClass.getMethod("uses", int.class);
                optionsBuilder = usesMethod.invoke(optionsBuilder, Integer.MAX_VALUE);
                Method buildMethod = optionsBuilderClass.getMethod("build");
                cachedClickOptions = buildMethod.invoke(optionsBuilder);
            }

            if (createMethod == null) {
                throw new NoSuchMethodException("Dialog.create method not found");
            }
            dialogApiSupported = true;
        } catch (Exception e) {
            dialogApiSupported = false;
                sharedClass.logger.logException(e);
        }
        try {
            String version = Bukkit.getServer().getMinecraftVersion();
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[1]);
            int minor = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

            // Mapping names based on Version
            // 1.20.5+ moved to "containerMenu" and uses modern mappings
            if (major > 20 || (major == 20 && minor >= 5)) {
                CONTAINER_MENU_FIELD = "containerMenu";
            } else {
                // Older versions (1.17 - 1.20.4)
                CONTAINER_MENU_FIELD = "activeContainer";
            }

            CONTAINER_ID_FIELD = "containerId";
            GET_TYPE_METHOD = "getType";

            // Pre-cache the Reflection objects
            Class<?> craftPlayerClass = Class.forName(Bukkit.getServer().getClass().getPackage().getName() + ".entity.CraftPlayer");
            getHandleMethod = craftPlayerClass.getMethod("getHandle");

            Class<?> nmsPlayerClass = getHandleMethod.getReturnType();
            activeContainerField = nmsPlayerClass.getField(CONTAINER_MENU_FIELD);

            Class<?> containerClass = activeContainerField.getType();
            containerIdField = containerClass.getField(CONTAINER_ID_FIELD);
            getTypeMethod = containerClass.getMethod(GET_TYPE_METHOD);

            protocolLibAvailable = Bukkit.getPluginManager().isPluginEnabled("ProtocolLib");
            if (protocolLibAvailable) {
                protocolManager = com.comphenix.protocol.ProtocolLibrary.getProtocolManager();
            }
        } catch (Exception e) {
            sharedClass.logger.log(Level.SEVERE, "Failed to cache minecraft classes: ", pluginLogger.RED);
            sharedClass.logger.logException(e);
        }
    }
}