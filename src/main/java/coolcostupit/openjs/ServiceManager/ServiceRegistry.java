/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.ServiceManager;

import coolcostupit.openjs.ServiceObjects.ScriptClassObject;

import javax.script.ScriptEngine;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ServiceRegistry {

    private static final Map<String, ScriptService> services = new ConcurrentHashMap<>();
    private static final String SERVICE_PACKAGE = "coolcostupit.openjs.Services";

    public static Object get(
            String name,
            String scriptName,
            ScriptEngine engine,
            ScriptClassObject scriptClass
    ) {

        String key = name.toLowerCase();

        ScriptService service = services.computeIfAbsent(key, k -> {
            return discoverService(name);
        });

        if (service == null) {
            throw new RuntimeException("Service not found: " + name);
        }

        return service.load(scriptName, engine, scriptClass);
    }

    private static ScriptService discoverService(String name) {
        String className = SERVICE_PACKAGE + "." + name + "Service";

        try {
            Class<?> clazz = Class.forName(className);

            if (!ScriptService.class.isAssignableFrom(clazz)) {
                throw new RuntimeException(
                        className + " does not implement ScriptService"
                );
            }

            return (ScriptService) clazz
                    .getDeclaredConstructor()
                    .newInstance();

        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to load service " + name + ": " + e.getMessage(), e
            );
        }
    }
}
