package coolcostupit.openjs.Services;

import coolcostupit.openjs.ServiceManager.ScriptService;
import coolcostupit.openjs.pluginbridges.ProtocolLibBridge;
import javax.script.ScriptEngine;

public class ProtocolLibService implements ScriptService {
    @Override
    public Object load(String scriptName, ScriptEngine engine) {
        return new ProtocolLibBridge(engine, scriptName);
    }
}