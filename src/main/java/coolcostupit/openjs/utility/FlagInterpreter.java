package coolcostupit.openjs.utility;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class FlagInterpreter {

    // check if the flag is present in the first few lines of the script
    public static boolean hasFlag(File scriptFile, String flag) {
        try (BufferedReader reader = new BufferedReader(new FileReader(scriptFile))) {
            String line;
            int maxLinesToCheck = 5; // I might change this in the future :P
            while ((line = reader.readLine()) != null && maxLinesToCheck > 0) {
                if (isFlagLine(line, flag)) {
                    return true;
                }
                maxLinesToCheck--;
            }
        } catch (IOException e) {
            // I hate this, but I am too lazy to do it better
            e.printStackTrace();
        }
        return false;
    }

    // check if the flag is present anywhere inside the script
    public boolean containsFlag(File scriptFile, String flag) {
        try (BufferedReader reader = new BufferedReader(new FileReader(scriptFile))) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (isFlagLine(line, flag)) {
                    return true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    // check if a line contains the specified flag
    private static boolean isFlagLine(String line, String flag) {
        return line.trim().equals("//!" + flag);
    }
}
