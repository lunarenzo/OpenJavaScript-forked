/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.modules;

import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import javax.script.ScriptEngineFactory;

public class ScriptEngine {
    private static ScriptEngineFactory FACTORY;
    private static ClassLoader composite; // cache it

    public static javax.script.ScriptEngine getEngine() {
        if (!(FACTORY instanceof NashornScriptEngineFactory)) {
            FACTORY = new NashornScriptEngineFactory();
        }

        if (composite == null) {
            composite = new ClassLoader(sharedClass.plugin.getClass().getClassLoader()) {
                @Override
                public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    try {
                        return super.loadClass(name, resolve);
                    } catch (ClassNotFoundException e) {
                        for (org.bukkit.plugin.Plugin p : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
                            try {
                                return p.getClass().getClassLoader().loadClass(name);
                            } catch (ClassNotFoundException ignored) {}
                        }
                        throw new ClassNotFoundException(name);
                    }
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
