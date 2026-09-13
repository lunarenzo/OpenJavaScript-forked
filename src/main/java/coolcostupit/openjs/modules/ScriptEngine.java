/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.modules;

import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import javax.script.ScriptEngineFactory;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class ScriptEngine {
    private static ScriptEngineFactory FACTORY;
    private static ClassLoader composite; // cache it

    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> NEGATIVE_CLASS_CACHE = ConcurrentHashMap.newKeySet();

    public static void clearClassCache() {
        CLASS_CACHE.clear();
        NEGATIVE_CLASS_CACHE.clear();
    }

    public static javax.script.ScriptEngine getEngine() {
        if (!(FACTORY instanceof NashornScriptEngineFactory)) {
            FACTORY = new NashornScriptEngineFactory();
        }

        if (composite == null) {
            composite = new ClassLoader(sharedClass.plugin.getClass().getClassLoader()) {
                @Override
                public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (NEGATIVE_CLASS_CACHE.contains(name)) {
                        throw new ClassNotFoundException(name);
                    }

                    Class<?> cached = CLASS_CACHE.get(name);
                    if (cached != null) {
                        return cached;
                    }

                    try {
                        Class<?> c = super.loadClass(name, resolve);
                        if (c != null) {
                            CLASS_CACHE.put(name, c);
                            return c;
                        }
                    } catch (ClassNotFoundException ignored) {}

                    for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                        try {
                            Class<?> c = p.getClass().getClassLoader().loadClass(name);
                            if (c != null) {
                                CLASS_CACHE.put(name, c);
                                return c;
                            }
                        } catch (ClassNotFoundException ignored) {}
                    }

                    if (sharedClass.LibImporterApi != null) {
                        Class<?> fromLib = sharedClass.LibImporterApi.findClass(name);
                        if (fromLib != null) {
                            CLASS_CACHE.put(name, fromLib);
                            return fromLib;
                        }
                    }

                    NEGATIVE_CLASS_CACHE.add(name);
                    throw new ClassNotFoundException(name);
                }
            };
        }

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(composite);
            return ((NashornScriptEngineFactory) FACTORY).getScriptEngine("--language=es6");
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
