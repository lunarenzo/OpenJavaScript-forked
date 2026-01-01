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

    public static javax.script.ScriptEngine getEngine() {
        if (!(FACTORY instanceof NashornScriptEngineFactory)) {
            FACTORY = new NashornScriptEngineFactory();
        }
        return FACTORY.getScriptEngine();
    }
}
