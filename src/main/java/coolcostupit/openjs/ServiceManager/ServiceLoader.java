/*
 * Copyright (c) 2025 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.ServiceManager;

import javax.script.ScriptEngine;

public class ServiceLoader {
    private final String scriptName;
    private final ScriptEngine scriptEngine;

    public ServiceLoader(ScriptEngine scriptEngine, String scriptName) {
        this.scriptEngine = scriptEngine;
        this.scriptName = scriptName;
    }

    public Object get(String ServiceName) {
        return ServiceRegistry.get(ServiceName, scriptName, scriptEngine);
    }
}
