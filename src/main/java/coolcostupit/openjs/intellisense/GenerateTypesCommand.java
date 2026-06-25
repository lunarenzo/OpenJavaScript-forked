/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */
package coolcostupit.openjs.intellisense;

import coolcostupit.openjs.modules.FoliaSupport;
import coolcostupit.openjs.modules.sharedClass;
import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.utility.chatColors;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class GenerateTypesCommand {

    private static boolean running = false;
    public static void run(CommandSender sender, JavaPlugin plugin) {
        if (running) {
            sender.sendMessage(chatColors.RED + "[OpenJS] Type generation is already running. Please wait.");
            return;
        }

        File outputDir = new File(plugin.getServer().getWorldContainer(), "OpenJS-VSCode-Types");
        sender.sendMessage(chatColors.LIGHT_BLUE + "[OpenJS] Starting VS Code type generation...");
        sender.sendMessage(chatColors.GRAY + "Output: " + outputDir.getAbsolutePath());
        sender.sendMessage(chatColors.GRAY + "This may take 30-120 seconds depending on loaded plugins.");

        running = true;

        FoliaSupport.runTask(plugin, () -> {
            try {
                ClassLoader serverClassLoader = Bukkit.class.getClassLoader();
                List<File> jarsToScan = collectJars(plugin, serverClassLoader);
                sender.sendMessage(chatColors.LIGHT_BLUE + "[OpenJS] Scanning " + jarsToScan.size() + " jar(s)...");

                TypeGenerator generator = new TypeGenerator(outputDir);
                File sourcesJar = downloadSourcesJar(plugin, outputDir);
                generator.generate(jarsToScan, serverClassLoader, sourcesJar);

                FoliaSupport.runTaskSynchronously(plugin, () -> {
                    sender.sendMessage(chatColors.GREEN + "[OpenJS] Type generation complete!");
                    sender.sendMessage(chatColors.GREEN + "Output folder: " + outputDir.getAbsolutePath());
                });

            } catch (Exception e) {
                sharedClass.logger.log(Level.SEVERE, "Type generation failed: " + e.getMessage(), pluginLogger.RED);
                FoliaSupport.runTaskSynchronously(plugin, () ->
                        sender.sendMessage(chatColors.RED + "[OpenJS] Type generation failed: " + e.getMessage()));
            } finally {
                running = false;
            }
        });
    }

    private static String fetchSnapshotTimestamp(String baseUrl) throws Exception {
        java.net.URL url = new java.net.URL(baseUrl + "maven-metadata.xml");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();

        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        if (conn.getResponseCode() != 200) return null;

        try (java.io.InputStream in = conn.getInputStream()) {
            String xml = new String(in.readAllBytes());

            java.util.regex.Pattern p1 =
                    java.util.regex.Pattern.compile("<timestamp>(.*?)</timestamp>");
            java.util.regex.Pattern p2 =
                    java.util.regex.Pattern.compile("<buildNumber>(.*?)</buildNumber>");

            java.util.regex.Matcher m1 = p1.matcher(xml);
            java.util.regex.Matcher m2 = p2.matcher(xml);

            if (!m1.find() || !m2.find()) return null;

            return m1.group(1) + "-" + m2.group(1);
        }
    }

    private static File downloadSourcesJar(JavaPlugin plugin, File outputDir) throws Exception {
        // Detect Paper version from server version string e.g. "git-Paper-xxx (MC: 1.21.4)"
        String version = plugin.getServer().getBukkitVersion(); // e.g. "1.21.4-R0.1-SNAPSHOT"
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File dest = new File(outputDir, "paper-api-sources.jar");
        if (dest.exists()) return dest; // cached

        String base = "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/" + version + "/";
        String url;
        String snapshotId = fetchSnapshotTimestamp(base);

        if (snapshotId != null && version.contains("SNAPSHOT")) {
            String baseVersion = version.replace("-SNAPSHOT", "");
            url = base + "paper-api-" + baseVersion + "-" + snapshotId + "-sources.jar";
        } else {
            url = base + "paper-api-" + version + "-sources.jar";
        }

        sharedClass.logger.log(Level.INFO, "Downloading Paper sources from: " + url, pluginLogger.LIGHT_BLUE);

        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "OpenJS-TypeGen");
            if (conn.getResponseCode() != 200) {
                sharedClass.logger.log(Level.WARNING, "Could not download sources jar (HTTP " + conn.getResponseCode() + "), javadoc will be skipped.", pluginLogger.ORANGE);
                return null;
            }
            try (java.io.InputStream in = conn.getInputStream();
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            sharedClass.logger.log(Level.INFO, "Sources jar downloaded.", pluginLogger.GREEN);
            return dest;
        } catch (Exception e) {
            sharedClass.logger.log(Level.WARNING, "Failed to download sources jar: " + e.getMessage() + ", javadoc will be skipped.", pluginLogger.ORANGE);
            return null;
        }
    }

    // Collect all jars that are relevant to scan.
    private static List<File> collectJars(JavaPlugin plugin, ClassLoader serverClassLoader) {
        List<File> jars = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. Server jar via Bukkit.class code source
        try {
            URL codeSource = Bukkit.class.getProtectionDomain().getCodeSource().getLocation();
            if (codeSource != null && codeSource.getProtocol().equals("file")) {
                File serverJar = new File(codeSource.toURI()).getCanonicalFile();
                if (serverJar.exists() && serverJar.getName().endsWith(".jar")
                        && seen.add(serverJar.getAbsolutePath())) {
                    jars.add(serverJar);
                    sharedClass.logger.log(Level.INFO, "Server jar located: " + serverJar.getName(), pluginLogger.BLUE);
                }
            }
        } catch (Exception e) {
            sharedClass.logger.log(Level.WARNING, "Could not locate server jar via Bukkit.class: " + e.getMessage(), pluginLogger.ORANGE);
        }

        // 2. Plugin jars
        File pluginsDir = new File(plugin.getServer().getWorldContainer(), "plugins");
        if (pluginsDir.exists()) {
            File[] pluginJars = pluginsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
            if (pluginJars != null) {
                for (File jar : pluginJars) {
                    if (seen.add(jar.getAbsolutePath())) jars.add(jar);
                }
            }
        }

        // 3. java.class.path jars (excluding JDK/JRE)
        String classPath = System.getProperty("java.class.path");
        if (classPath != null) {
            for (String cp : classPath.split(File.pathSeparator)) {
                File f = new File(cp);
                if (!f.exists() || !f.getName().endsWith(".jar")) continue;
                if (!seen.add(f.getAbsolutePath())) continue;
                String abs = f.getAbsolutePath().replace('\\', '/');
                if (!abs.contains("/jdk/") && !abs.contains("/jre/")
                        && !abs.contains("\\jdk\\") && !abs.contains("\\jre\\")) {
                    jars.add(f);
                }
            }
        }

        // 4. custom libraries
        File libsDir = new File(plugin.getDataFolder(), "libs");
        if (libsDir.exists()) {
            File[] libJars = libsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
            if (libJars != null) {
                for (File jar : libJars) {
                    if (seen.add(jar.getAbsolutePath())) jars.add(jar);
                }
            }
        }

        return jars;
    }
}