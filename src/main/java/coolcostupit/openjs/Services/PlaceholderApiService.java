/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.Services;

import coolcostupit.openjs.ServiceManager.ScriptService;
import coolcostupit.openjs.ServiceObjects.ScriptClassObject;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.scriptWrapper;
import coolcostupit.openjs.modules.sharedClass;
import coolcostupit.openjs.pluginbridges.PlaceHolderApiJS;
import coolcostupit.openjs.utility.scriptUtils;

import javax.script.ScriptEngine;
import java.util.logging.Level;

public class PlaceholderApiService implements ScriptService {
    public static PlaceHolderApiJS backend;

    @Override
    public Object load(String scriptName, ScriptEngine engine, ScriptClassObject scriptClass) {
        try {
            if (backend == null) {
                if (!sharedClass.IsPapiLoaded) {
                    sharedClass.logger.scriptlog(Level.WARNING, scriptName, "PlaceholderApi plugin missing or not loaded!", pluginLogger.ORANGE);
                } else {
                    backend = new PlaceHolderApiJS();
                    sharedClass.logger.scriptlog(Level.INFO, scriptName, "PlaceholderApi support loaded!", pluginLogger.GREEN);
                }
            }

            // Expose backend temporarily
            engine.put("__PlaceholderAPI_Backend", backend);
            Object api = scriptUtils.evalJavascriptArray(engine, scriptName, """
                {
                    registerPlaceholder: function(placeholderPrefix, handler) {
                        __PlaceholderAPI_Backend.registerPlaceholder(placeholderPrefix, handler, script.MainRelativePath, scriptEngine);
                    },
                    unregisterPlaceholder: function(placeholderPrefix) {
                        __PlaceholderAPI_Backend.unregisterPlaceholder(placeholderPrefix, script.MainRelativePath);
                    },
                    parseString: function(player, text) {
                        return __PlaceholderAPI_Backend.parseString(player, text);
                    }
                }
                """);

            // Cleanup on unload
            scriptWrapper.addToCleanupMap(scriptClass.MainRelativePath, () -> backend.unregisterPlaceholders(scriptClass.MainRelativePath));
            return api;
        } catch (Exception e) {
            sharedClass.logger.scriptlog(Level.WARNING, scriptName, "Failed to load PlaceholderAPI service: " + e.getMessage(), pluginLogger.ORANGE);
            throw new RuntimeException(e);
        }
    }
}
