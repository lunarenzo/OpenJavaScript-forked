/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */
package coolcostupit.openjs.modules;

import coolcostupit.openjs.logging.pluginLogger;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.zip.*;

public class scriptPackManager {
    private static final String TEMPLATE_JAR_URL = "https://gitlab.com/spidermodders/openjs-scriptpacker-template/-/raw/main/PublicBuild/releaseBuild.jar?ref_type=heads&inline=false";

    public static List<File> getScriptPacks() {
        File scriptsFolder = sharedClass.scriptFolder;
        List<File> packs = new ArrayList<>();
        if (scriptsFolder == null || !scriptsFolder.exists()) return packs;

        File[] folders = scriptsFolder.listFiles(File::isDirectory);
        if (folders == null) return packs;

        for (File folder : folders) {
            if (isScriptPack(folder)) packs.add(folder);
        }
        return packs;
    }


    public static File getScriptPackByName(String name) {
        for (File pack : getScriptPacks()) {
            if (pack.getName().equalsIgnoreCase(name)) return pack;
        }
        return null;
    }

    public static String getPluginExtractor(File scriptPack) {
        if (scriptPack == null || !scriptPack.isDirectory()) return null;
        File infoJson = new File(scriptPack, "info.json");
        if (!infoJson.exists()) return null;
        try {
            String json = Files.readString(infoJson.toPath(), StandardCharsets.UTF_8);
            Object val  = parseJsonObject(json).get("pluginExtractor");
            return val != null ? String.valueOf(val) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Converts a scriptPack into a distributable plugin jar.
     *   Downloads the OpenJS ScriptPacker template jar.
     *   Saves the result as {<packName>.jar} inside {OpenJS/convertedPlugins/}.
     *   Merges every key present in info.json into the template's
     *   plugin.yml (existing keys are overwritten).
     *   Zips the scriptPack's contents and injects
     *   the resulting archive into the jar.
     *
     * @param scriptPack the scriptPack directory to convert
     * @throws Exception on download failure, I/O error, or invalid pack
     */
    public static void convertScriptPack(File scriptPack) throws Exception {
        pluginLogger logger = sharedClass.logger;

        if (!isScriptPack(scriptPack)) {
            throw new IllegalArgumentException("'" + scriptPack.getName() + "' is not a valid scriptPack (needs info.json + main.js)");
        }

        // Ensure the output folder exists
        File outputFolder = new File(sharedClass.plugin.getDataFolder(), "convertedPlugins");
        if (!outputFolder.exists() && !outputFolder.mkdirs()) {
            throw new IOException("Failed to create 'convertedPlugins' folder");
        }

        String packName    = scriptPack.getName();
        File   outputJar   = new File(outputFolder, packName + ".jar");
        File   templateJar = new File(outputFolder, "_template_tmp.jar");

        // download the template jar
        logger.log(Level.INFO, "Downloading ScriptPacker template...", pluginLogger.LIGHT_BLUE);
        Files.deleteIfExists(templateJar.toPath()); // clean up any leftover from a previous failed run
        downloadFile(TEMPLATE_JAR_URL, templateJar);

        // parse info.json (optional; packs may omit it)
        Map<String, Object> infoMap = new LinkedHashMap<>();
        File infoJson = new File(scriptPack, "info.json");
        if (infoJson.exists()) {
            infoMap = parseJsonObject(Files.readString(infoJson.toPath(), StandardCharsets.UTF_8));
        }

        // zip the scriptPack's contents (files inside the folder, not the folder itself)
        logger.log(Level.INFO, "Zipping scriptPack contents...", pluginLogger.LIGHT_BLUE);
        byte[] scriptsZipBytes = zipFolderContents(scriptPack);

        // rebuild the jar with a patched plugin.yml and an injected scripts.zip
        logger.log(Level.INFO, "Building output jar...", pluginLogger.LIGHT_BLUE);
        buildOutputJar(templateJar, outputJar, infoMap, scriptsZipBytes, packName);

        // Cleanup temp file
        Files.deleteIfExists(templateJar.toPath());
        logger.log(Level.INFO, "Successfully generated: convertedPlugins/" + packName + ".jar", pluginLogger.GREEN);
    }

    // Private helpers

    private static boolean isScriptPack(File folder) {
        if (folder == null || !folder.isDirectory()) return false;
        return new File(folder, "info.json").exists() && (new File(folder, "main.js").exists() || new File(folder, "Main.js").exists());
    }

    private static void downloadFile(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = openConnection(urlStr);

        // GitLab raw file URLs redirect to a CDN, follow the Location header
        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_MOVED_PERM
                || status == 307 || status == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            conn = openConnection(location);
        }

        try (InputStream     in  = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[8_192];
            int    n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "OpenJS-ScriptPackManager/1.0");
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static byte[] zipFolderContents(File folder) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            addToZip(folder, folder.toPath(), zos);
        }
        return baos.toByteArray();
    }

    private static void addToZip(File current, Path basePath, ZipOutputStream zos) throws IOException {
        File[] children = current.listFiles();
        if (children == null) return;

        for (File child : children) {
            String entryName = basePath.relativize(child.toPath())
                    .toString()
                    .replace(File.separatorChar, '/');

            if (child.isDirectory()) {
                zos.putNextEntry(new ZipEntry(entryName + '/'));
                zos.closeEntry();
                addToZip(child, basePath, zos); // recurse
            } else {
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(child.toPath(), zos);
                zos.closeEntry();
            }
        }
    }


    private static void buildOutputJar(File templateJar, File outputJar, Map<String, Object> infoMap, byte[] scriptsZipBytes, String packName) throws IOException {
        try (ZipFile zipIn = new ZipFile(templateJar); ZipOutputStream zos  = new ZipOutputStream(new FileOutputStream(outputJar))) {

            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String   name  = entry.getName();

                if (name.equals("plugin.yml")) {
                    // Patch plugin.yml with info.json overrides
                    byte[] patched;
                    try (InputStream in = zipIn.getInputStream(entry)) {
                        patched = buildPluginYml(in, infoMap, packName).getBytes(StandardCharsets.UTF_8);
                    }
                    zos.putNextEntry(new ZipEntry("plugin.yml"));
                    zos.write(patched);
                    zos.closeEntry();

                } else {
                    zos.putNextEntry(new ZipEntry(name));
                    if (!entry.isDirectory()) {
                        try (InputStream in = zipIn.getInputStream(entry)) {
                            byte[] buf = new byte[8_192];
                            int    n;
                            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                        }
                    }
                    zos.closeEntry();
                }
            }

            // Inject scriptPack into the jar
            zos.putNextEntry(new ZipEntry(packName + ".zip"));
            zos.write(scriptsZipBytes);
            zos.closeEntry();
        }
    }

    private static String buildPluginYml(InputStream existingYml, Map<String, Object> infoMap, String packName) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(readStream(existingYml));
        } catch (Exception ignored) {
            // Start fresh when the template's YAML is unreadable
        }

        // sanitise to strip characters Bukkit rejects (spaces, most special chars)
        if (infoMap.containsKey("name")) {
            yaml.set("name", sanitizePluginName(String.valueOf(infoMap.get("name"))));
        } else if (!yaml.contains("name")) {
            yaml.set("name", sanitizePluginName(packName));
        }

        setIfPresent(yaml, infoMap, "version", String::valueOf);
        setIfPresent(yaml, infoMap, "description", String::valueOf);
        setIfPresent(yaml, infoMap, "api-version", scriptPackManager::formatApiVersion);

        if (infoMap.containsKey("authors")) {
            Object raw = infoMap.get("authors");
            yaml.set("authors", raw instanceof List ? raw
                    : Collections.singletonList(String.valueOf(raw)));
        }
        if (infoMap.containsKey("folia-supported")) {
            yaml.set("folia-supported", infoMap.get("folia-supported"));
        }

        return yaml.saveToString();
    }

    private static void setIfPresent(YamlConfiguration yaml, Map<String, Object> map, String key, Function<Object, Object> mapper) {
        if (map.containsKey(key)) yaml.set(key, mapper.apply(map.get(key)));
    }

    private static String sanitizePluginName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static Object formatApiVersion(Object raw) {
        if (raw instanceof Double d) {
            return String.valueOf(d); // Double.toString already removes trailing zeros
        }
        return String.valueOf(raw);
    }

    /** Reads an InputStream fully into a UTF-8 string. */
    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4_096];
        int    n;
        while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toString(StandardCharsets.UTF_8);
    }

    // Minimal recursive-descent JSON parser (no external dependencies)
    static Map<String, Object> parseJsonObject(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        json = json.trim();
        if (json.isEmpty() || json.charAt(0) != '{') return result;

        int[] i = {1};
        while (i[0] < json.length()) {
            skipWs(json, i);
            if (i[0] >= json.length() || json.charAt(i[0]) == '}') break;
            if (json.charAt(i[0]) != '"') break;

            String key = consumeString(json, i);
            skipWs(json, i);
            if (i[0] < json.length() && json.charAt(i[0]) == ':') i[0]++;
            skipWs(json, i);

            Object value = consumeValue(json, i);
            result.put(key, value);

            skipWs(json, i);
            if (i[0] < json.length() && json.charAt(i[0]) == ',') i[0]++;
        }
        return result;
    }

    private static Object consumeValue(String s, int[] i) {
        if (i[0] >= s.length()) return null;
        char c = s.charAt(i[0]);

        if (c == '"') return consumeString(s, i);
        if (c == '[') return consumeArray(s, i);
        if (c == '{') return consumeNestedObject(s, i);

        if (s.startsWith("true",  i[0])) { i[0] += 4; return Boolean.TRUE;  }
        if (s.startsWith("false", i[0])) { i[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null",  i[0])) { i[0] += 4; return null;          }

        return consumeNumber(s, i);
    }

    private static String consumeString(String s, int[] i) {
        i[0]++; // skip opening '"'
        StringBuilder sb = new StringBuilder();
        while (i[0] < s.length()) {
            char c = s.charAt(i[0]++);
            if (c == '"') break;
            if (c == '\\' && i[0] < s.length()) {
                char esc = s.charAt(i[0]++);
                sb.append(switch (esc) {
                    case '"'  -> '"';
                    case '\\' -> '\\';
                    case '/'  -> '/';
                    case 'n'  -> '\n';
                    case 'r'  -> '\r';
                    case 't'  -> '\t';
                    default   -> esc;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<Object> consumeArray(String s, int[] i) {
        i[0]++; // skip '['
        List<Object> list = new ArrayList<>();
        skipWs(s, i);
        while (i[0] < s.length() && s.charAt(i[0]) != ']') {
            list.add(consumeValue(s, i));
            skipWs(s, i);
            if (i[0] < s.length() && s.charAt(i[0]) == ',') i[0]++;
            skipWs(s, i);
        }
        if (i[0] < s.length()) i[0]++; // skip ']'
        return list;
    }

    private static Map<String, Object> consumeNestedObject(String s, int[] i) {
        int start = i[0];
        int depth = 0;
        while (i[0] < s.length()) {
            char c = s.charAt(i[0]++);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) break;
            } else if (c == '"') {
                // Skip string literals so braces inside them are not miscounted
                while (i[0] < s.length()) {
                    char sc = s.charAt(i[0]++);
                    if (sc == '\\') i[0]++;
                    else if (sc == '"') break;
                }
            }
        }
        return parseJsonObject(s.substring(start, i[0]));
    }

    private static Object consumeNumber(String s, int[] i) {
        int start = i[0];
        while (i[0] < s.length()) {
            char c = s.charAt(i[0]);
            if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) break;
            i[0]++;
        }
        String token = s.substring(start, i[0]).trim();
        try {
            if (token.contains(".") || token.contains("e") || token.contains("E"))
                return Double.parseDouble(token);
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            return token; // fall back to raw string on parse failure
        }
    }

    private static void skipWs(String s, int[] i) {
        while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++;
    }
}