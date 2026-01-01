/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.modules;

import coolcostupit.openjs.ServiceObjects.ScriptClassObject;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.utility.scriptUtils;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static coolcostupit.openjs.modules.sharedClass.plugin;
import static org.bukkit.Bukkit.getServer;

public class InternalSystems {
    private final String ScriptName;
    private final ScriptEngine Engine;
    private final ScriptClassObject scriptClass;
    static private final Map<String, List<Listener>> eventListenersMap = new HashMap<>();
    private final Map<String, Object> requireCache;

    public InternalSystems(String scriptName, ScriptEngine engine, ScriptClassObject scriptClass) {
        this.ScriptName = scriptName;
        this.Engine = engine;
        this.requireCache = new HashMap<>();
        this.scriptClass = scriptClass;
    }

    public Object importClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            String simpleName = clazz.getSimpleName();
            //sharedClass.logger.scriptlog(Level.INFO, ScriptName, "Class found for import: " + className, pluginLogger.LIGHT_BLUE);
            return scriptUtils.importJavaToJsGC(Engine, ScriptName, clazz);
        } catch (ClassNotFoundException e) {
            sharedClass.logger.scriptlog(Level.WARNING, ScriptName, "Class not found for import: " + className, pluginLogger.ORANGE);
        }
        return null;
    }

    public Object requireScript(String relativePath) {
        if (!scriptManager.isRelativePath(relativePath)) {
            File targetFile = new File(relativePath);
            if (!targetFile.exists()) {
                sharedClass.logger.scriptlog(Level.SEVERE, ScriptName, "Require path is not relative and file does not exist: " + relativePath, pluginLogger.ORANGE);
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
                sharedClass.logger.scriptlog(Level.WARNING, ScriptName, "[" + absoluteTarget + "] Did not return anything.", pluginLogger.ORANGE);
                return null;
            } else {
                return result;
            }
        } else {
            sharedClass.logger.scriptlog(Level.SEVERE, ScriptName, "Failed to require script: " + absoluteTarget, pluginLogger.ORANGE);
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
                        sharedClass.logger.scriptlog(Level.SEVERE, ScriptName, ex.getMessage(), pluginLogger.RED);
                    }
                }, plugin);

                eventListenersMap.computeIfAbsent(ScriptName, k -> new ArrayList<>()).add(listener);
                return listener;
            } else {
                sharedClass.logger.scriptlog(Level.WARNING, ScriptName, "Class " + eventClassName + " is not an Event.", pluginLogger.ORANGE);
            }
        } catch (ClassNotFoundException e) {
            sharedClass.logger.scriptlog(Level.WARNING, ScriptName, "Failed to register event " + eventClassName + ": " + e.getMessage(), pluginLogger.ORANGE);
        }
        return null;
    }
}
