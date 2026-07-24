/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.ScriptGlobals;

import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.sharedClass;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Stream;

public class LibImporterApi {
    private static final String DEFAULT_REPO = "https://repo1.maven.org/maven2/";
    private static final Set<String> SKIP_SCOPES = Set.of("test", "provided", "system");

    private final Map<String, URLClassLoader> cachedLibs = new ConcurrentHashMap<>();
    private final Map<String, URLClassLoader> cachedSets = new ConcurrentHashMap<>();

    private final Path libFolderPath;
    private final Path dependenciesFolderPath;
    private final pluginLogger Logger;

    private WatchService watchService;
    private Thread watcherThread;

    public LibImporterApi() {
        this.Logger = sharedClass.logger;
        this.libFolderPath = sharedClass.plugin.getDataFolder().toPath().resolve("libraries");
        this.dependenciesFolderPath = sharedClass.plugin.getDataFolder().toPath().resolve("dependencies");
        preLoad();
    }

    public void preLoad() {

        // TODO: This is a migration code for users from 1.4.0 to 1.5.0. Remove in 1.6.0
        File oldLibsFolder = sharedClass.plugin.getDataFolder().toPath().resolve("Libs").toFile();
        if (oldLibsFolder.exists()) {
            try {
                libFolderPath.toFile().mkdirs();
                Files.move(oldLibsFolder.toPath(), libFolderPath, StandardCopyOption.REPLACE_EXISTING);
                Logger.debug("Successfully renamed old Libs folder to 'libraries'");
            } catch (IOException e) {
                Logger.debug("Failed to move old Libs folder to new libraries folder: " + e.getMessage());
                Logger.debug("Trying to move old content in Libs folder to new libraries folder");
                try {
                    libFolderPath.toFile().mkdirs();

                    if (Files.exists(oldLibsFolder.toPath())) {
                        try (Stream<Path> stream = Files.walk(oldLibsFolder.toPath())) {
                            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                                try {
                                    Path target = libFolderPath.resolve(oldLibsFolder.toPath().relativize(path));

                                    if (Files.isDirectory(path)) {
                                        Files.createDirectories(target);
                                    } else {
                                        Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
                                    }

                                    if (!path.equals(oldLibsFolder.toPath())) {
                                        Files.delete(path);
                                    }
                                } catch (IOException err) {
                                    throw new UncheckedIOException(err);
                                }
                            });
                        }

                        Files.deleteIfExists(oldLibsFolder.toPath());
                        Logger.debug("Successfully moved old Libs folder content to new libraries folder");
                    }
                } catch (IOException ex) {
                    Logger.log(Level.SEVERE, "Failed to move old Libs folder content to new libraries folder: " + ex.getMessage(), pluginLogger.RED);
                }
            }
        }

        libFolderPath.toFile().mkdirs();
        dependenciesFolderPath.toFile().mkdirs();

        loadAllLibs();
        loadAllDependencySets();
        startWatcher();
    }

    private void startWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            libFolderPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);

            watcherThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        WatchKey key = watchService.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();
                            String fileName = event.context().toString();

                            if (!fileName.toLowerCase().endsWith(".jar")) continue;
                            if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                                loadLib(libFolderPath.resolve(fileName).toFile());
                            } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                                unloadLib(fileName);
                            }
                        }
                        key.reset();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "Libs-Watcher");

            watcherThread.setDaemon(true);
            watcherThread.start();
        } catch (IOException e) {
            Logger.log(Level.SEVERE, "Failed to start Libs folder watcher", pluginLogger.RED);
        }
    }

    private void loadAllLibs() {
        File[] jars = libFolderPath.toFile().listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null) return;
        for (File jar : jars) loadLib(jar);
    }

    private void loadAllDependencySets() {
        File[] setFolders = dependenciesFolderPath.toFile().listFiles(File::isDirectory);
        if (setFolders == null) return;
        for (File setFolder : setFolders) loadDependencySetFromDisk(setFolder.getName());
    }

    public void loadLib(File jarFile) {
        String name = jarFile.getName();
        if (cachedLibs.containsKey(name)) {
            Logger.debug("Library already loaded: " + name);
            return;
        }
        try {
            URLClassLoader loader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, this.getClass().getClassLoader());
            cachedLibs.put(name, loader);
            Logger.debug("Loaded lib: " + name);
        } catch (IOException e) {
            Logger.log(Level.SEVERE, "Failed to load lib: " + name + " - " + e.getMessage(), pluginLogger.RED);
        }
    }

    public URLClassLoader getLib(String libName) {
        return cachedLibs.get(libName.endsWith(".jar") ? libName : (libName + ".jar"));
    }

    public void unloadLib(String libName) {
        String fileName = libName.endsWith(".jar") ? libName : (libName + ".jar");
        URLClassLoader loader = cachedLibs.remove(fileName);
        if (loader != null) {
            try {
                loader.close();
                Logger.log(Level.INFO, "Unloaded and removed lib: " + fileName, pluginLogger.GREEN);
            } catch (IOException e) {
                Logger.log(Level.WARNING, "Failed to close loader for lib: " + fileName, pluginLogger.ORANGE);
            }
        } else {
            Logger.log(Level.WARNING, "Tried to unload non-existent lib: " + fileName, pluginLogger.ORANGE);
        }
    }

    public Class<?> findClass(String className) {
        for (URLClassLoader loader : cachedLibs.values()) {
            try {
                return loader.loadClass(className);
            } catch (ClassNotFoundException ignored) {}
        }
        for (URLClassLoader loader : cachedSets.values()) {
            try {
                return loader.loadClass(className);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    private record Coord(String groupId, String artifactId, String version) {
        String fileName() { return artifactId + "-" + version + ".jar"; }
    }

    private String setKey(String groupId, String artifactId, String version) {
        return groupId + "_" + artifactId + "_" + version;
    }

    public boolean downloadDependency(String groupId, String artifactId, String version) {
        return downloadDependency(groupId, artifactId, version, DEFAULT_REPO);
    }

    public boolean downloadDependency(String groupId, String artifactId, String version, String repoBaseUrl) {
        if (groupId == null || groupId.isBlank()) {
            Logger.log(Level.SEVERE, "downloadDependency: groupId is required", pluginLogger.RED);
            return false;
        }

        String key = setKey(groupId, artifactId, version);
        if (cachedSets.containsKey(key)) {
            Logger.debug("Dependency set already loaded: " + key);
            return true;
        }

        Path setFolder = dependenciesFolderPath.resolve(key);
        if (setFolder.toFile().isDirectory() && loadDependencySetFromDisk(key)) {
            return true;
        }

        String repo = repoBaseUrl.endsWith("/") ? repoBaseUrl : repoBaseUrl + "/";
        Map<String, Coord> resolved = new LinkedHashMap<>();
        try {
            resolve(new Coord(groupId, artifactId, version), repo, resolved, new HashSet<>());
        } catch (Exception e) {
            Logger.log(Level.SEVERE, "Failed to resolve dependency tree for " + groupId + ":" + artifactId + ":" + version, pluginLogger.RED);
            Logger.logException(e);
            return false;
        }

        setFolder.toFile().mkdirs();
        List<File> jarFiles = new ArrayList<>();
        for (Coord c : resolved.values()) {
            File jarFile = setFolder.resolve(c.fileName()).toFile();
            if (!jarFile.exists()) {
                String jarUrl = repo + c.groupId().replace('.', '/') + "/" + c.artifactId() + "/" + c.version() + "/" + c.fileName();
                try {
                    downloadFile(jarUrl, jarFile, setFolder);
                    Logger.debug("Downloaded dependency: " + c.fileName());
                } catch (IOException e) {
                    Logger.log(Level.SEVERE, "Failed to download " + c.fileName() + " from " + jarUrl, pluginLogger.RED);
                    Logger.logException(e);
                    return false;
                }
            }
            jarFiles.add(jarFile);
        }

        return loadDependencySet(key, jarFiles, resolved.keySet());
    }

    private boolean loadDependencySetFromDisk(String key) {
        if (cachedSets.containsKey(key)) return true;

        File setFolder = dependenciesFolderPath.resolve(key).toFile();
        File[] jars = setFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) return false;

        return loadDependencySet(key, Arrays.asList(jars), null);
    }

    public boolean loadDependencySetFromCustomDir(File dir) {
        String key = dir.getName();
        if (cachedSets.containsKey(key)) return true;

        File[] jars = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) return false;

        return loadDependencySet(key, Arrays.asList(jars), null);
    }

    private boolean loadDependencySet(String key, List<File> jarFiles, Set<String> labelForLog) {
        try {
            URL[] urls = new URL[jarFiles.size()];
            for (int i = 0; i < jarFiles.size(); i++) urls[i] = jarFiles.get(i).toURI().toURL();
            URLClassLoader loader = new URLClassLoader(urls, this.getClass().getClassLoader());
            URLClassLoader previous = cachedSets.putIfAbsent(key, loader);
            if (previous != null) {
                loader.close();
                return true;
            }
            Logger.debug("Loaded dependency set '" + key + "' with " + urls.length + " jar(s)" + (labelForLog != null ? ": " + labelForLog : ""));
            return true;
        } catch (Exception e) {
            Logger.log(Level.SEVERE, "Failed to create classloader for " + key, pluginLogger.RED);
            Logger.logException(e);
            return false;
        }
    }

    private NodeList getDirectDependencies(Document pom) {
        Element root = pom.getDocumentElement();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element el && el.getTagName().equals("dependencies")) {
                return el.getElementsByTagName("dependency");
            }
        }
        return new EmptyNodeList();
    }

    private void resolve(Coord coord, String repo, Map<String, Coord> resolved, Set<String> visiting) throws Exception {
        String key = coord.groupId() + ":" + coord.artifactId();
        if (resolved.containsKey(key)) return;
        if (!visiting.add(key)) return;
        resolved.put(key, coord);

        String pomUrl = repo + coord.groupId().replace('.', '/') + "/" + coord.artifactId()
                + "/" + coord.version() + "/" + coord.artifactId() + "-" + coord.version() + ".pom";

        Document pom;
        try {
            pom = fetchXml(pomUrl);
        } catch (IOException e) {
            Logger.debug("No POM found for " + key + ":" + coord.version() + " (treating as leaf)");
            visiting.remove(key);
            return;
        }

        Map<String, String> properties = new HashMap<>();
        Map<String, String> dependencyManagement = new HashMap<>();
        collectAncestorData(pom, repo, properties, dependencyManagement, new HashSet<>());
        properties.put("project.version", coord.version());

        NodeList depNodes = getDirectDependencies(pom);
        for (int i = 0; i < depNodes.getLength(); i++) {
            Element dep = (Element) depNodes.item(i);

            String scope = text(dep, "scope");
            if (scope != null && SKIP_SCOPES.contains(scope)) continue;
            if ("true".equalsIgnoreCase(text(dep, "optional"))) continue;

            String g = resolveProp(text(dep, "groupId"), properties);
            String a = resolveProp(text(dep, "artifactId"), properties);
            String v = resolveProp(text(dep, "version"), properties);

            if (v == null && g != null && a != null) {
                v = dependencyManagement.get(g + ":" + a);
            }

            if (g == null || a == null || v == null || v.isBlank()) {
                Logger.debug("Skipping dependency with unresolved coordinates: " + g + ":" + a + ":" + v);
                continue;
            }

            resolve(new Coord(g, a, v), repo, resolved, visiting);
        }

        visiting.remove(key);
    }

    private void collectAncestorData(Document pom, String repo, Map<String, String> properties, Map<String, String> dependencyManagement, Set<String> visitedParents) throws Exception {
        NodeList parentNodes = pom.getElementsByTagName("parent");
        if (parentNodes.getLength() > 0) {
            Element parentEl = (Element) parentNodes.item(0);
            String pg = text(parentEl, "groupId");
            String pa = text(parentEl, "artifactId");
            String pv = text(parentEl, "version");

            if (pg != null && pa != null && pv != null) {
                String parentKey = pg + ":" + pa + ":" + pv;
                if (visitedParents.add(parentKey)) {
                    String parentPomUrl = repo + pg.replace('.', '/') + "/" + pa + "/" + pv + "/" + pa + "-" + pv + ".pom";
                    try {
                        Document parentPom = fetchXml(parentPomUrl);
                        collectAncestorData(parentPom, repo, properties, dependencyManagement, visitedParents);
                        properties.putAll(readProperties(parentPom));
                        dependencyManagement.putAll(readDependencyManagement(parentPom, properties));
                    } catch (IOException e) {
                        Logger.debug("No parent POM found for " + parentKey);
                    }
                }
            }
        }
        dependencyManagement.putAll(readDependencyManagement(pom, properties));
    }

    private Map<String, String> readDependencyManagement(Document pom, Map<String, String> properties) {
        Map<String, String> map = new HashMap<>();
        NodeList dmNodes = pom.getElementsByTagName("dependencyManagement");
        if (dmNodes.getLength() == 0) return map;
        Element dm = (Element) dmNodes.item(0);
        NodeList depsInDm = dm.getElementsByTagName("dependency");
        for (int i = 0; i < depsInDm.getLength(); i++) {
            Element dep = (Element) depsInDm.item(i);
            String g = resolveProp(text(dep, "groupId"), properties);
            String a = resolveProp(text(dep, "artifactId"), properties);
            String v = resolveProp(text(dep, "version"), properties);
            if (g != null && a != null && v != null) map.put(g + ":" + a, v);
        }
        return map;
    }

    private Map<String, String> readProperties(Document pom) {
        Map<String, String> props = new HashMap<>();
        NodeList propsNode = pom.getElementsByTagName("properties");
        if (propsNode.getLength() == 0) return props;
        NodeList children = propsNode.item(0).getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element el) props.put(el.getTagName(), el.getTextContent().trim());
        }
        return props;
    }

    private String resolveProp(String raw, Map<String, String> properties) {
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.startsWith("${") && raw.endsWith("}")) {
            return properties.get(raw.substring(2, raw.length() - 1));
        }
        return raw;
    }

    private String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() == 0 ? null : nl.item(0).getTextContent().trim();
    }

    private Document fetchXml(String url) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setInstanceFollowRedirects(true);
        try {
            int status = conn.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status + " for " + url);
            try (InputStream in = conn.getInputStream()) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                DocumentBuilder builder = factory.newDocumentBuilder();
                return builder.parse(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    private void downloadFile(String urlStr, File targetFile, Path tempDir) throws IOException {
        URL url = new URL(urlStr);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);

        Path tempFile;
        try {
            int status = conn.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status + " while fetching " + urlStr);

            tempFile = Files.createTempFile(tempDir, "download-", ".tmp");
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            conn.disconnect();
        }

        Files.move(tempFile, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static class EmptyNodeList implements NodeList {
        public Node item(int index) { return null; }
        public int getLength() { return 0; }
    }

    public void shutdown() {
        if (watcherThread != null) watcherThread.interrupt();
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }
        cachedLibs.values().forEach(loader -> {
            try { loader.close(); } catch (IOException ignored) {}
        });
        cachedSets.values().forEach(loader -> {
            try { loader.close(); } catch (IOException ignored) {}
        });
        cachedLibs.clear();
        cachedSets.clear();
    }
}