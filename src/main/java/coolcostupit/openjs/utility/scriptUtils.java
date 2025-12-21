/*
 * Copyright (c) 2025 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.utility;

import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.sharedClass;

import java.security.SecureRandom;
import java.util.logging.Level;

public class scriptUtils {
    private static final String LETTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        }
        return sb.toString();
    }

    public static Object evalJavascriptArray(javax.script.ScriptEngine engine, String scriptName, String jsCode) {
        try {
            return engine.eval("""
                (function() {
                    return (""" + jsCode + ");})();"
            );
        } catch (Exception e) {
            sharedClass.logger.scriptlog(
                    Level.SEVERE,
                    scriptName,
                    "Failed to parse internal code: " + e.getMessage(),
                    pluginLogger.ORANGE
            );
            throw new RuntimeException(e);
        }
    }

    public static Object executeJsCode(javax.script.ScriptEngine engine, String scriptName, String jsCode) {
        try {
            return engine.eval("(" + jsCode + ");})();");
        } catch (Exception e) {
            sharedClass.logger.scriptlog(
                    Level.SEVERE,
                    scriptName,
                    "Failed to parse internal code: " + e.getMessage(),
                    pluginLogger.ORANGE
            );
            throw new RuntimeException(e);
        }
    }
}
