/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.ServiceManager;

import coolcostupit.openjs.ServiceObjects.ScriptClassObject;

import javax.script.ScriptEngine;

public class ServiceLoader {
    private final String scriptName;
    private final ScriptEngine scriptEngine;
    private final ScriptClassObject scriptClass;

    public ServiceLoader(ScriptEngine scriptEngine, String scriptName, ScriptClassObject scriptClass) {
        this.scriptEngine = scriptEngine;
        this.scriptName = scriptName;
        this.scriptClass = scriptClass;
    }

    public Object get(String ServiceName) {
        return ServiceRegistry.get(ServiceName, scriptName, scriptEngine, scriptClass);
    }
}
