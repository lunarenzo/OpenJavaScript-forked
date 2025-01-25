package coolcostupit.openjs.modules;

import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import javax.script.ScriptEngineFactory;

public class ScriptEngine {
    private ScriptEngine() {
    }

    private static ScriptEngineFactory FACTORY;

    public static javax.script.ScriptEngine getEngine() {
        if (!(FACTORY instanceof NashornScriptEngineFactory)) {
            FACTORY = new NashornScriptEngineFactory();
        }
        return FACTORY.getScriptEngine();
    }
}
