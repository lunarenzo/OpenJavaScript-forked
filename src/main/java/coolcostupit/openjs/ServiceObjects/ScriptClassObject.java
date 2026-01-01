/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.ServiceObjects;

import coolcostupit.openjs.modules.scriptManager;
import coolcostupit.openjs.modules.sharedClass;

import java.io.File;

public class ScriptClassObject {

    public String Name;             // script.js
    public File File;               // full file reference
    public File MainFolder;         // folder containing main.js OR scripts root
    public File MainScript;         // main.js or Main.js (nullable)
    public final String MainRelativePath; // the original relative path, use this internally for GC

    public ScriptClassObject(String relativePath) {
        setPath(relativePath);
        this.MainRelativePath = scriptManager.getRelativePath(this.File);
    }

    public void setPath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            clear();
            return;
        }

        this.File = scriptManager.stringToScript(relativePath);
        if (this.File == null) {
            clear();
            return;
        }

        this.Name = File.getName();

        // Resolve main folder + main script
        resolveMainFolderAndScript(File);
    }

    private void resolveMainFolderAndScript(File scriptFile) {
        File current = scriptFile.getParentFile();
        File scriptsRoot = sharedClass.scriptFolder;

        while (current != null && !current.equals(scriptsRoot)) {
            File mainLower = new File(current, "main.js");
            File mainUpper = new File(current, "Main.js");

            if (mainLower.exists()) {
                this.MainFolder = current;
                this.MainScript = mainLower;
                return;
            }

            if (mainUpper.exists()) {
                this.MainFolder = current;
                this.MainScript = mainUpper;
                return;
            }

            current = current.getParentFile();
        }

        // No main.js found → fallback to scripts root
        this.MainFolder = scriptsRoot;
        this.MainScript = null;
    }

    private void clear() {
        this.Name = null;
        this.File = null;
        this.MainFolder = null;
        this.MainScript = null;
    }

    public String getName() {
        return Name;
    }

    public File getFile() {
        return File;
    }

    public File getMainFolder() {
        return MainFolder;
    }

    public String getRelativePath() {
        return scriptManager.getRelativePath(this.File);
    }

    public File getMainScript() {
        return MainScript;
    }

    public boolean hasMainScript() {
        return MainScript != null;
    }
}
