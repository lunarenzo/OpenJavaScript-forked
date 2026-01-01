/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.Services;

import coolcostupit.openjs.ServiceManager.ScriptService;
import coolcostupit.openjs.ServiceObjects.ScriptClassObject;
import coolcostupit.openjs.pluginbridges.ProtocolLibBridge;
import javax.script.ScriptEngine;

public class ProtocolLibService implements ScriptService {
    @Override
    public Object load(String scriptName, ScriptEngine engine, ScriptClassObject scriptClass) {
        return new ProtocolLibBridge(engine, scriptClass.MainRelativePath);
    }
}