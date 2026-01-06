/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.Services;

import coolcostupit.openjs.ServiceManager.ScriptService;
import coolcostupit.openjs.ServiceObjects.FileManagerObject;
import coolcostupit.openjs.ServiceObjects.ScriptClassObject;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.scriptWrapper;
import coolcostupit.openjs.modules.sharedClass;
import coolcostupit.openjs.utility.scriptUtils;

import javax.script.ScriptEngine;
import java.util.logging.Level;

public class FileManagerService implements ScriptService {
    @Override
    public Object load(String scriptName, ScriptEngine engine, ScriptClassObject scriptClass) {
        try {
            FileManagerObject manager = new FileManagerObject(scriptClass, engine);
            engine.put("__FileManagerObject", manager);
            Object api = scriptUtils.evalJavascriptArray(engine, scriptName, """
                {
                    fileExists: function(relativePath) {
                        return __FileManagerObject.fileExists(relativePath);
                    },
                    read: function(relativePath) {
                        return __FileManagerObject.readFile(relativePath);
                    },
                    write: function(relativePath, data) {
                        return __FileManagerObject.writeToFile(relativePath, data);
                    },
                    createFile: function(relativePath) {
                        return __FileManagerObject.createFile(relativePath);
                    },
                    createFolder: function(relativePath) {
                        return __FileManagerObject.createFolder(relativePath);
                    },
                    listenOnPath: function(relativePath, listenerType, handler) {
                        return __FileManagerObject.listenOnPath(relativePath, listenerType, { e: handler });
                    },
                    clearFileListeners: function() {
                        __FileManagerObject.clearFileListeners();
                    }
                }
            """);

            scriptWrapper.addToCleanupMap(scriptClass.MainRelativePath, manager::clearFileListeners);
            return api;
        } catch (Exception e) {
            sharedClass.logger.scriptlog(Level.SEVERE, scriptClass.RelativePath, "Failed to load FileManagerService: " + e.getMessage(), pluginLogger.RED);
            throw new RuntimeException(e);
        }
    }
}
