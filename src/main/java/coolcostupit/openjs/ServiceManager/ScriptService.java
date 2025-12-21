/*
 * Copyright (c) 2025 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.ServiceManager;
import javax.script.ScriptEngine;

// Service Template
public interface ScriptService {
    Object load(String scriptName, ScriptEngine engine);
    default void unload(String scriptName) {}
}

