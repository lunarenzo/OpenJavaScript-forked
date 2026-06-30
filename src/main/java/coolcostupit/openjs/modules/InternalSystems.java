/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.modules;

import coolcostupit.openjs.ServiceObjects.ScriptClassObject;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.utility.chatColors;
import coolcostupit.openjs.utility.scriptUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static coolcostupit.openjs.modules.sharedClass.plugin;
import static org.bukkit.Bukkit.getServer;

public class InternalSystems {
    private final String ScriptName;
    private final ScriptEngine Engine;
    private final String ScriptId;
    private final ScriptClassObject scriptClass;
    private final Map<String, Object> requireCache;
    private static final pluginLogger Logger = sharedClass.logger;
    private static CommandMap cachedCommandMap = null;
    static private final Map<String, List<Listener>> eventListenersMap = new HashMap<>();

    public InternalSystems(String scriptName, ScriptEngine engine, ScriptClassObject scriptClass) {
        this.ScriptName = scriptName;
        this.ScriptId = scriptClass.MainRelativePath;
        this.Engine = engine;
        this.requireCache = new HashMap<>();
        this.scriptClass = scriptClass;
    }

    public Object importClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return scriptUtils.importJavaToJsGC(Engine, ScriptName, clazz);
        } catch (ClassNotFoundException e) {
            Logger.scriptlog(Level.WARNING, ScriptName, "Class not found for import: " + className, pluginLogger.ORANGE);
        }
        return null;
    }

    public Object requireScript(String relativePath) {
        if (!scriptManager.isRelativePath(relativePath)) {
            File targetFile = new File(relativePath);
            if (!targetFile.exists()) {
                Logger.scriptlog(Level.SEVERE, ScriptName, "Require path is not relative and file does not exist: " + relativePath, pluginLogger.ORANGE);
                return null;
            }
            relativePath = scriptManager.getRelativePath(targetFile);
        }

        String parentFolder = scriptManager.getRelativeParentFolder(scriptClass.File);
        String absoluteTarget = parentFolder + "/" + relativePath.replace(File.separatorChar, '/'); // Normalize to forward slashes

        if (requireCache.containsKey(absoluteTarget)) {
            return requireCache.get(absoluteTarget);
        }

        File targetFile = scriptManager.stringToScript(absoluteTarget);
        String javascriptCode = scriptManager.readCode(absoluteTarget);

        if (javascriptCode != null && targetFile != null) {
            File originalScriptFile = scriptClass.File;

            scriptClass.setPath(absoluteTarget);
            Object result = scriptUtils.executeJsCode(Engine, absoluteTarget, javascriptCode);
            requireCache.put(absoluteTarget, result);
            scriptClass.setPath(scriptManager.getRelativePath(originalScriptFile));

            if (result == null) {
                Logger.scriptlog(Level.WARNING, ScriptName, "[" + absoluteTarget + "] Did not return anything.", pluginLogger.ORANGE);
                return null;
            } else {
                return result;
            }
        } else {
            Logger.scriptlog(Level.SEVERE, ScriptName, "Failed to require script: " + absoluteTarget, pluginLogger.ORANGE);
            return null;
        }
    }

    static public List<Listener> getEventListenersFromScript(String scriptName) {
        return eventListenersMap.getOrDefault(scriptName, null);
    }

    static public void unregisterListener(Listener listener, String scriptName) {
        HandlerList.unregisterAll(listener);
        List<Listener> listeners = eventListenersMap.get(scriptName);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                eventListenersMap.remove(scriptName);
            }
        }
    }

    public void unregisterListenerInternal(Listener listener) {
        HandlerList.unregisterAll(listener);
        List<Listener> listeners = eventListenersMap.get(ScriptName);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                eventListenersMap.remove(ScriptName);
            }
        }
    }

    static public void unregisterListenersFromScript(String scriptName) {
        List<Listener> activeListeners = getEventListenersFromScript(scriptName);
        if (activeListeners != null) {
            List<Listener> listenersToRemove = new ArrayList<>(activeListeners);
            for (Listener listener : listenersToRemove) {
                unregisterListener(listener, scriptName);
            }
        }
    }

    static public void unregisterAllListeners() {
        for (Map.Entry<String, List<Listener>> entry : eventListenersMap.entrySet()) {
            List<Listener> listeners = entry.getValue();
            for (Listener listener : listeners) {
                HandlerList.unregisterAll(listener);
            }
        }
        eventListenersMap.clear();
    }

    @SuppressWarnings("unused")
    public Listener registerEvent(String eventClassName, Object handler) {
        try {
            Class<?> eventClass = Class.forName(eventClassName);
            if (Event.class.isAssignableFrom(eventClass)) {
                Class<? extends Event> eventClassCasted = (Class<? extends Event>) eventClass;
                Listener listener = new EventListenerWrapper(Engine, handler, plugin);
                getServer().getPluginManager().registerEvent(eventClassCasted, listener, EventPriority.NORMAL, (l, e) -> {
                    try {
                        ((Invocable) Engine).invokeMethod(handler, "handleEvent", e);
                    } catch (ScriptException | NoSuchMethodException ex) {
                        Logger.scriptlog(Level.SEVERE, ScriptName, "Failed to handle event:", pluginLogger.RED);
                        Logger.logScriptError(ex, ScriptName);
                    }
                }, plugin);

                eventListenersMap.computeIfAbsent(ScriptId, k -> new ArrayList<>()).add(listener);
                return listener;
            } else {
                Logger.scriptlog(Level.WARNING, ScriptName, "Class " + eventClassName + " is not an Event.", pluginLogger.ORANGE);
            }
        } catch (ClassNotFoundException e) {
            Logger.scriptlog(Level.WARNING, ScriptName, "Failed to register event " + eventClassName + ": " + e.getMessage(), pluginLogger.ORANGE);
        }
        return null;
    }

    private static Method getMethod(Class<?> clazz, String methodName) throws NoSuchMethodException {
        try {
            return clazz.getDeclaredMethod(methodName);
        } catch (NoSuchMethodException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass == null) {
                throw e;
            } else {
                return getMethod(superClass, methodName);
            }
        }
    }

    private static Map<String, Command> getKnownCommands(CommandMap commandMap) throws Exception {
        Field field = SimpleCommandMap.class.getDeclaredField("knownCommands");
        field.setAccessible(true);
        return (Map<String, Command>) field.get(commandMap);
    }

    private CommandMap getCommandMap() {
        if (cachedCommandMap != null) return cachedCommandMap;
        CommandMap commandMap = null;

        try {
            Field f = Bukkit.getPluginManager().getClass().getDeclaredField("commandMap");
            f.setAccessible(true);
            commandMap = (CommandMap) f.get(Bukkit.getPluginManager());
            cachedCommandMap = commandMap;
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException | SecurityException e) {
            Logger.scriptlog(Level.SEVERE, ScriptName, "Failed to load get CommandMap:", pluginLogger.RED);
            Logger.logScriptError(e, ScriptName);
        }

        return commandMap;
    }

    private static final AtomicBoolean isSyncScheduled = new AtomicBoolean(false);

    private static void invokeSyncCommands() {
        if (isSyncScheduled.compareAndSet(false, true)) {
            FoliaSupport.ScheduleTask(plugin, () -> {
                isSyncScheduled.set(false);
                try {
                    Class<?> serverClass = Bukkit.getServer().getClass();
                    Method method = getMethod(serverClass, "syncCommands");
                    method.setAccessible(true);
                    method.invoke(Bukkit.getServer());
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    Logger.log(Level.WARNING, "Failed to sync commands:", pluginLogger.RED);
                    Logger.logException(e, Level.WARNING);
                }
            }, 1L);
        }
    }

    public void registerCommand(String commandName, Object commandHandler, @Nullable String permission) {
        try {
            CommandMap commandMap = getCommandMap();
            Command dynamicCommand = new Command(commandName) {
                @Override
                public boolean execute(@NotNull CommandSender sender, @NotNull String label, String[] args) {
                    if (!testPermission(sender)) return true; // permission check, may be redundant
                    try {
                        ((Invocable) Engine).invokeMethod(commandHandler, "onCommand", sender, args);
                    } catch (Exception e) {
                        sender.sendMessage(chatColors.RED + "An error occurred while executing the command: " + e.getMessage());
                        Logger.scriptlog(Level.SEVERE, ScriptName, "Error in script command execution for " + commandName + ":", pluginLogger.RED);
                        Logger.logScriptError(e, ScriptName);
                    }
                    return true;
                }

                @Override
                public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, String[] args) {
                    if (commandHandler instanceof Bindings && ((Bindings) commandHandler).containsKey("onTabComplete")) {
                        try {
                            return (List<String>) ((Invocable) Engine).invokeMethod(commandHandler, "onTabComplete", sender, args);
                        } catch (Exception e) {
                            Logger.logScriptError("Error during tab-completion for command " + commandName + ":", ScriptName);
                            Logger.logScriptError(e, ScriptName);
                        }
                    }
                    return super.tabComplete(sender, alias, args);
                }
            };

            if (permission != null && !permission.isEmpty()) {
                dynamicCommand.setPermission(permission);
            }

            commandMap.register(plugin.getName(), dynamicCommand);
            scriptWrapper.addToCleanupMap(scriptClass.MainRelativePath, () -> unregisterCommand(commandName));
            invokeSyncCommands(); // Update command map for tab completion

            if (sharedClass.configUtil.getConfigFromBuffer("LogCustomCommandsActivity", true)) {
                Logger.scriptlog(Level.INFO, ScriptName, "Registered command: " + commandName, pluginLogger.GREEN);
            }
        } catch (Exception e) {
            Logger.scriptlog(Level.WARNING, ScriptName, "Failed to register command " + commandName + ":", pluginLogger.RED);
            Logger.logException(e, Level.WARNING);
        }
    }

    public void unregisterCommand(String commandName) {
        try {
            CommandMap commandMap = getCommandMap();
            Map<String, Command> knownCommands = getKnownCommands(commandMap);
            Command existing = knownCommands.remove(commandName);

            if (existing != null) {
                boolean unregistered = existing.unregister(commandMap);
                if (unregistered) {
                    if (sharedClass.configUtil.getConfigFromBuffer("LogCustomCommandsActivity", true)) {
                        Logger.scriptlog(Level.INFO,ScriptName, "Unregistered command: " + commandName, pluginLogger.GREEN);
                    }
                }
            }
        } catch (Exception exception) {
            Logger.scriptlog(Level.WARNING, ScriptName, "Failed to unregister command " + commandName + ":", pluginLogger.RED);
            Logger.logException(exception, Level.WARNING);
        }
    }
}
