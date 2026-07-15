/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 */
package coolcostupit.openjs.intellisense;

import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.sharedClass;

import java.io.*;
import java.lang.reflect.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Generates TypeScript .d.ts declaration files by collecting all classes.
 */
public class TypeGenerator {

    private static final pluginLogger logger = sharedClass.logger;
    private final Map<String, String> javadocCache = new HashMap<>();
    private final File outputDir;
    private int totalGenerated = 0;
    private int totalFailed = 0;
    private final Set<String> failedClasses = new HashSet<>();

    private static final Set<String> BLOCKED_PREFIXES = new HashSet<>(Arrays.asList(
            "sun.", "com.sun.", "jdk.", "jdk.internal.",
            "net.minecraft.", // NMS: version-specific, not useful
            "org.bukkit.craftbukkit." // CraftBukkit internals
    ));

    public TypeGenerator(File outputDir) {
        this.outputDir = outputDir;
    }

    private List<String> scanJdkModules() {
        List<String> refs = new ArrayList<>();
        // Only the modules relevant to scripting - java.base covers io/util/lang/nio/etc.
        Set<String> modules = new HashSet<>(Arrays.asList("java.base"));

        Map<String, List<String>> classesByModule = new HashMap<>();
        for (String moduleName : modules) {
            List<String> names = getClassNamesFromModule(moduleName);
            if (!names.isEmpty()) classesByModule.put(moduleName, names);
        }

        for (Map.Entry<String, List<String>> entry : classesByModule.entrySet()) {
            String ns = "java_lang"; // or sanitizeNamespace(entry.getKey())
            File dir = new File(outputDir, ns);
            // Use the platform classloader, which can see java.base classes
            ClassLoader platformLoader = ClassLoader.getPlatformClassLoader();
            List<String> ref = generateForClasses(entry.getValue(), platformLoader, dir, ns);
            refs.addAll(ref);
            logger.log("[TypeGen] JDK module " + entry.getKey() + " generated " + ref.size() + " files");
        }
        return refs;
    }

    private List<String> getClassNamesFromModule(String moduleName) {
        List<String> names = new ArrayList<>();
        Optional<Module> moduleOpt = ModuleLayer.boot().findModule(moduleName);
        if (moduleOpt.isEmpty()) return names;
        Module module = moduleOpt.get();

        try {
            // Walk the jrt:/ filesystem for this module's classes
            java.nio.file.FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path moduleRoot = fs.getPath("/modules/" + moduleName);
            if (!Files.exists(moduleRoot)) return names;

            try (var stream = Files.walk(moduleRoot)) {
                stream.filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> !p.toString().contains("$")) // skip inner/anon classes, same as jar scan
                        .forEach(p -> {
                            String rel = moduleRoot.relativize(p).toString();
                            String className = rel.replace('/', '.').replace(".class", "");
                            names.add(className);
                        });
            }
        } catch (Exception e) {
            logger.debug("[TypeGen] Failed to read module " + moduleName + ": " + e.getMessage());
        }
        return names;
    }

    private File findJdkSourcesZip() {
        String javaHome = System.getProperty("java.home");
        File srcZip = new File(javaHome, "lib/src.zip");
        if (srcZip.exists()) return srcZip;
        logger.debug("[TypeGen] JDK src.zip not found at " + srcZip.getAbsolutePath());
        return null;
    }

    private void loadJavadocFromSourcesJar(File sourcesJar) {
        if (!sourcesJar.exists()) return;
        logger.log("[TypeGen] Loading javadoc from: " + sourcesJar.getName());
        try (JarFile jf = new JarFile(sourcesJar)) {
            // Need the total count up front to compute percentages
            int total = 0;
            Enumeration<JarEntry> counter = jf.entries();
            while (counter.hasMoreElements()) {
                if (counter.nextElement().getName().endsWith(".java")) total++;
            }

            if (total == 0) {
                logger.log("[TypeGen] No .java entries found in " + sourcesJar.getName());
                return;
            }

            int processed = 0;
            int lastReportedPercent = -1;

            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".java")) continue;

                try (java.io.InputStream is = jf.getInputStream(entry)) {
                    String source = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    parseJavadocFromSource(source);
                } catch (Exception ignored) {}

                processed++;
                int percent = (processed * 100) / total;
                int bucket = (percent / 5) * 5; // round down to nearest 5%

                if (bucket != lastReportedPercent && bucket > 0) {
                    lastReportedPercent = bucket;
                    logger.log("[TypeGen] Loading javadocs from " + sourcesJar.getName() + " " + bucket+"%");
                }
            }
        } catch (Exception e) {
            logger.debug("[TypeGen] Failed to read sources jar: " + e.getMessage());
        }
        logger.log("[TypeGen] Loaded " + javadocCache.size() + " javadoc entries");
    }

    private void parseJavadocFromSource(String source) {
        // Match javadoc blocks followed by a method or field declaration
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "/\\*\\*(.*?)\\*/\\s*(?:@[^\\n]*\\n\\s*)*(?:public\\s+)?(?:static\\s+)?(?:final\\s+)?(?:default\\s+)?(?:abstract\\s+)?(?:[\\w<>\\[\\],\\s]+?)\\s+(\\w+)\\s*\\(",
                java.util.regex.Pattern.DOTALL
        );
        java.util.regex.Matcher m = pattern.matcher(source);
        while (m.find()) {
            String doc = m.group(1).trim();
            String methodName = m.group(2);
            doc = doc.replaceAll("(?m)^\\s*\\*\\s?", "").trim();
            doc = doc.replaceAll("(?s)\\{@[^}]+}", "").trim();
            if (!doc.isEmpty() && !methodName.isEmpty()) {
                javadocCache.put(methodName, doc);
            }
        }
    }

    public void generate(List<File> jarFiles, ClassLoader serverClassLoader, File sourcesJar) throws IOException {
        outputDir.mkdirs();

        List<String> allRefs = new ArrayList<>();
        Set<String> seenJars = new HashSet<>();

        File jdkSources = findJdkSourcesZip();
        if (jdkSources != null) loadJavadocFromSourcesJar(jdkSources);
        if (sourcesJar != null) loadJavadocFromSourcesJar(sourcesJar);

        // 1. Deep-scan all classloader URLs (catches org.bukkit loaded by Paper's classloader)
        logger.debug("[TypeGen] Deep scanning classloader hierarchy...");
        List<String> deepRefs = deepScanClassLoader(serverClassLoader, seenJars);
        logger.debug("[TypeGen] Deep scan found " + deepRefs.size() + " reference files");
        List<String> jdkRefs = scanJdkModules();
        logger.log("[TypeGen] Deep scan found " + jdkRefs.size() + " reference files");
        allRefs.addAll(deepRefs);
        allRefs.addAll(jdkRefs);

        // 2. Scan provided jars (plugins, libs)
        for (File jar : jarFiles) {
            if (!jar.exists() || !jar.getName().endsWith(".jar")) continue;
            if (!seenJars.add(jar.getAbsolutePath())) continue; // already done

            String ns = sanitizeNamespace(jar.getName().replace(".jar", ""));
            logger.debug("[TypeGen] Scanning jar: " + jar.getName());
            try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, serverClassLoader)) {
                List<String> names = getClassNamesFromJar(jar);
                File dir = new File(outputDir, ns);
                List<String> refs = generateForClasses(names, loader, dir, ns);
                allRefs.addAll(refs);
                logger.debug("[TypeGen] Jar " + jar.getName() + " generated " + refs.size() + " files");
            } catch (Exception e) {
                logger.debug("[TypeGen] Failed to scan " + jar.getName() + ": " + e.getMessage());
            }
        }

        // Log failures
        if (!failedClasses.isEmpty()) {
            logger.debug("[TypeGen] Failed to generate " + totalFailed + " classes. Examples: " + failedClasses.stream().limit(10).collect(Collectors.joining(", ")));
        }

        // 3. Generate importClass overloads index from everything found
        writeImportClassOverloads();
        writeIndexFile(allRefs);
        logger.log("[TypeGen] Done. Generated " + totalGenerated + " declarations, failed " + totalFailed + " classes.");
    }

    /**
     * Walk every URL in the classloader hierarchy and scan each jar.
     */
    private List<String> deepScanClassLoader(ClassLoader root, Set<String> seenJars) {
        List<String> refs = new ArrayList<>();

        List<URL> urls = collectUrls(root);
        logger.debug("[TypeGen] Found " + urls.size() + " URLs in classloader hierarchy");

        for (URL url : urls) {
            if (!url.getProtocol().equals("file")) continue;
            File f = new File(url.getFile());
            if (!f.exists() || !f.getName().endsWith(".jar")) continue;
            if (!seenJars.add(f.getAbsolutePath())) continue;

            String ns = sanitizeNamespace(f.getName().replace(".jar", ""));
            logger.debug("[TypeGen] Scanning (deep): " + f.getName());
            try (URLClassLoader loader = new URLClassLoader(new URL[]{f.toURI().toURL()}, root)) {
                List<String> names = getClassNamesFromJar(f);
                logger.debug("[TypeGen] Jar " + f.getName() + " contains " + names.size() + " classes");
                File dir = new File(outputDir, ns);
                List<String> ref = generateForClasses(names, loader, dir, ns);
                refs.addAll(ref);
                logger.debug("[TypeGen] Deep jar " + f.getName() + " generated " + ref.size() + " files");
            } catch (Exception e) {
                logger.debug("[TypeGen] Deep scan failed for " + f.getName() + ": " + e.getMessage());
            }
        }

        return refs;
    }

    private List<URL> collectUrls(ClassLoader loader) {
        List<URL> urls = new ArrayList<>();
        Set<ClassLoader> visited = new HashSet<>();
        ClassLoader current = loader;

        while (current != null && visited.add(current)) {
            logger.debug("[TypeGen] Examining ClassLoader: " + current.getClass().getName());

            // Standard URLClassLoader
            if (current instanceof URLClassLoader) {
                URL[] classLoaderUrls = ((URLClassLoader) current).getURLs();
                logger.debug("[TypeGen] Found " + classLoaderUrls.length + " URLs in URLClassLoader");
                urls.addAll(Arrays.asList(classLoaderUrls));
            }

            tryGetUrlsViaReflection(current, urls, "getURLs");
            tryGetUrlsViaUcp(current, urls);
            tryGetUrlsViaReflection(current, urls, "getClassPath");

            current = current.getParent();
        }

        // Also check system classloader
        try {
            String classPath = System.getProperty("java.class.path");
            if (classPath != null) {
                for (String cp : classPath.split(File.pathSeparator)) {
                    File f = new File(cp);
                    if (f.exists() && f.getName().endsWith(".jar")) {
                        urls.add(f.toURI().toURL());
                    }
                }
            }
        } catch (Exception ignored) {}

        return urls;
    }

    private void tryGetUrlsViaReflection(ClassLoader loader, List<URL> out, String methodName) {
        try {
            Method m = loader.getClass().getMethod(methodName);
            Object result = m.invoke(loader);
            if (result instanceof URL[]) {
                out.addAll(Arrays.asList((URL[]) result));
            } else if (result instanceof Collection) {
                for (Object obj : (Collection<?>) result) {
                    if (obj instanceof URL) out.add((URL) obj);
                }
            }
        } catch (Exception ignored) {}
    }

    private void tryGetUrlsViaUcp(ClassLoader loader, List<URL> out) {
        Class<?> cls = loader.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField("ucp");
                f.setAccessible(true);
                Object ucp = f.get(loader);
                if (ucp != null) {
                    Method getURLs = ucp.getClass().getMethod("getURLs");
                    Object result = getURLs.invoke(ucp);
                    if (result instanceof URL[]) out.addAll(Arrays.asList((URL[]) result));
                }
                return;
            } catch (Exception ignored) {}
            cls = cls.getSuperclass();
        }
    }

    private List<String> generateForClasses(List<String> classNames, ClassLoader loader, File outDir, String namespace) {
        outDir.mkdirs();
        List<String> refs = new ArrayList<>();

        // Group by package
        Map<String, List<String>> byPackage = new LinkedHashMap<>();
        for (String name : classNames) {
            if (isBlocked(name)) continue;
            byPackage.computeIfAbsent(getPackage(name), k -> new ArrayList<>()).add(name);
        }

        logger.log("[TypeGen] Processing " + classNames.size() + " classes in " + byPackage.size() + " packages");

        for (Map.Entry<String, List<String>> entry : byPackage.entrySet()) {
            String pkg = entry.getKey();
            List<String> names = entry.getValue();

            String fileName = pkg.replace('.', '_') + ".d.ts";
            File outFile = new File(outDir, fileName);
            String relPath = "./" + namespace + "/" + fileName;

            try {
                StringBuilder sb = new StringBuilder();
                sb.append("// Auto-generated by OpenJS /oj genvsextension\n");
                sb.append("// Package: ").append(pkg).append("\n\n");

                boolean wrote = false;
                for (String className : names) {
                    try {
                        Class<?> clazz = loader.loadClass(className);
                        String decl = generateDeclaration(clazz);
                        if (decl != null) {
                            sb.append(decl).append("\n");
                            wrote = true;
                            totalGenerated++;
                        }
                    } catch (NoClassDefFoundError | ClassNotFoundException e) {
                        // Class exists in jar but has dependencies not available
                        totalFailed++;
                        failedClasses.add(className);
                    } catch (Throwable t) {
                        totalFailed++;
                        if (totalFailed <= 5) {
                            logger.debug("[TypeGen] Failed to load class " + className + ": " + t.getMessage());
                        }
                    }
                }

                if (wrote) {
                    Files.writeString(outFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
                    refs.add(relPath);
                }
            } catch (IOException e) {
                logger.log("[TypeGen] Failed to write " + outFile + ": " + e.getMessage());
            }
        }

        return refs;
    }

    private String generateDeclaration(Class<?> clazz) {
        if (clazz.isAnonymousClass() || clazz.isSynthetic()) return null;
        if (!Modifier.isPublic(clazz.getModifiers())) return null;

        String simpleName = sanitizeName(clazz.getSimpleName());
        if (simpleName.isEmpty()) return null;

        // tsName is just the simple class name now, namespaces provide the package
        String tsName = simpleName;
        String packageName = getPackage(clazz.getName());

        StringBuilder inner = new StringBuilder();
        inner.append("/** `importClass(\"").append(clazz.getName()).append("\")` */\n");

        if (clazz.isEnum()) {
            inner.append("class ").append(tsName).append(" {\n");
            try {
                for (Object c : clazz.getEnumConstants()) {
                    inner.append("  static readonly ").append(sanitizeName(c.toString()))
                            .append(": ").append(tsName).append(";\n");
                }
            } catch (Throwable ignored) {}
            inner.append("  name(): string;\n");
            inner.append("  toString(): string;\n");
            inner.append("  static valueOf(name: string): ").append(tsName).append(";\n");
            inner.append("  static values(): ").append(tsName).append("[];\n");
        } else if (clazz.isInterface()) {
            inner.append("interface ").append(tsName).append(" {\n");
            appendMethods(inner, clazz, false, tsName);
        } else {
            inner.append("class ").append(tsName).append(" {\n");
            appendConstructors(inner, clazz, tsName);
            appendFields(inner, clazz);
            appendMethods(inner, clazz, true, tsName);
        }
        inner.append("}\n");

        // Wrap in nested namespace blocks: org.bukkit -> namespace org { namespace bukkit { ... } }
        String[] parts = packageName.split("\\.");
        StringBuilder sb = new StringBuilder();
        sb.append("}\n");

        String indent = "";
        for (String part : parts) {
            sb.append(indent).append("declare namespace ").append(sanitizeName(part)).append(" {\n");
            indent += "  ";
        }

        for (String line : inner.toString().split("\n", -1)) {
            if (!line.isEmpty()) sb.append(indent).append(line).append("\n");
        }

        for (int i = 0; i < parts.length; i++) {
            indent = indent.substring(2);
            sb.append(indent).append("}\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private void appendConstructors(StringBuilder sb, Class<?> clazz, String tsName) {
        for (Constructor<?> c : clazz.getConstructors()) {
            if (!Modifier.isPublic(c.getModifiers())) continue;
            sb.append("  constructor(").append(paramsToTs(c.getParameterTypes())).append(");\n");
        }
    }

    private void appendFields(StringBuilder sb, Class<?> clazz) {
        for (Field f : clazz.getFields()) {
            if (!Modifier.isPublic(f.getModifiers())) continue;
            boolean isStatic = Modifier.isStatic(f.getModifiers());
            boolean isFinal = Modifier.isFinal(f.getModifiers());
            sb.append(isStatic ? "  static " : "  ")
                    .append(isFinal ? "readonly " : "")
                    .append(sanitizeName(f.getName())).append(": ")
                    .append(typeToTs(f.getType())).append(";\n");
        }
    }

    private void appendMethods(StringBuilder sb, Class<?> clazz, boolean includeStatic, String tsName) {
        Set<String> seen = new HashSet<>();
        for (Method m : clazz.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (m.isSynthetic() || m.isBridge()) continue;
            if (m.getDeclaringClass() == Object.class && !m.getName().equals("toString")) continue;

            boolean isStatic = Modifier.isStatic(m.getModifiers());
            if (isStatic && !includeStatic) continue;

            String sig = m.getName() + "(" + Arrays.stream(m.getParameterTypes())
                    .map(Class::getSimpleName).collect(Collectors.joining(",")) + ")";
            if (!seen.add(sig)) continue;

            String doc = javadocCache.get(m.getName());
            if (doc != null) {
                String safeDoc = doc.replace("*/", "*\\/").replace("\n", "\n   * ");
                sb.append("  /** ").append(safeDoc).append(" */\n");
            }

            sb.append(isStatic ? "  static " : "  ")
                    .append(sanitizeName(m.getName())).append("(")
                    .append(paramsToTs(m.getParameterTypes())).append("): ")
                    .append(typeToTs(m.getReturnType())).append(";\n");
        }
    }

    private String paramsToTs(Class<?>[] params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("arg").append(i).append(": ").append(typeToTs(params[i]));
        }
        return sb.toString();
    }

    private String typeToTs(Class<?> t) {
        if (t == null || t == void.class || t == Void.class) return "void";
        if (t == boolean.class || t == Boolean.class) return "boolean";
        if (t == String.class || t == char.class || t == Character.class) return "string";
        if (t.isPrimitive() || Number.class.isAssignableFrom(t)) return "number";
        if (t.isArray()) return typeToTs(t.getComponentType()) + "[]";
        if (t == Object.class) return "any";
        if (java.util.Collection.class.isAssignableFrom(t)) return "any[]";
        if (java.util.Map.class.isAssignableFrom(t)) return "Record<string, any>";
        if (java.util.Optional.class.isAssignableFrom(t)) return "any | null";
        if (!Modifier.isPublic(t.getModifiers())) return "any";

        String pkg = getPackage(t.getName());
        String simple = sanitizeName(t.getSimpleName());
        String namespacedPkg = Arrays.stream(pkg.split("\\.")).map(this::sanitizeName).collect(Collectors.joining("."));

        return namespacedPkg + "." + simple;
    }

    private boolean isBlocked(String name) {
        for (String prefix : BLOCKED_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private String getPackage(String className) {
        int dot = className.lastIndexOf('.');
        return dot > 0 ? className.substring(0, dot) : "default_pkg";
    }

    private String sanitizeName(String name) {
        if (name == null) return "";
        int dollar = name.lastIndexOf('$');
        if (dollar >= 0) name = name.substring(dollar + 1);
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String sanitizeNamespace(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    public static List<String> getClassNamesFromJar(File jar) {
        List<String> names = new ArrayList<>();
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String n = e.getName();
                if (n.endsWith(".class") && !n.contains("$")) {
                    names.add(n.replace('/', '.').replace(".class", ""));
                }
            }
        } catch (IOException e) {
            logger.debug("[TypeGen] Failed to read jar " + jar.getName() + ": " + e.getMessage());
        }
        return names;
    }

    // importClass overloads
    private void writeImportClassOverloads() throws IOException {
        List<String> classNames = new ArrayList<>();
        collectGeneratedClassNames(outputDir, classNames);

        if (classNames.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("// importClass overloads - auto-generated by /oj genvsextension\n");
        sb.append("// Provides autocomplete for importClass(\"...\") string arguments\n\n");

        Collections.sort(classNames);

        for (String name : classNames) {
            String pkg = name.substring(0, name.lastIndexOf('.') > 0 ? name.lastIndexOf('.') : name.length());
            String simple = sanitizeName(name.substring(name.lastIndexOf('.') + 1));
            String namespacedPkg = Arrays.stream(pkg.split("\\."))
                    .map(this::sanitizeName)
                    .collect(Collectors.joining("."));
            String tsName = namespacedPkg + "." + simple;
            sb.append("declare function importClass(className: \"").append(name)
                    .append("\"): typeof ").append(tsName).append(";\n");
        }

        sb.append("declare function importClass(className: string): any;\n");
        Files.writeString(
                new File(outputDir, "importClass-overloads.d.ts").toPath(),
                sb.toString(),
                StandardCharsets.UTF_8
        );
    }

    private void collectGeneratedClassNames(File dir, List<String> out) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectGeneratedClassNames(f, out);
            } else if (f.getName().endsWith(".d.ts") && !f.getName().equals("importClass-overloads.d.ts") && !f.getName().equals("index.d.ts")) {
                try {
                    String content = Files.readString(f.toPath());
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("/\\*\\* `importClass\\(\"([^\"]+)\"\\)`").matcher(content);
                    while (m.find()) out.add(m.group(1));
                } catch (IOException ignored) {}
            }
        }
    }

    private void writeIndexFile(List<String> refs) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("// OpenJS Generated Types Index\n");
        sb.append("// Auto-generated by /oj genvsextension\n\n");
        sb.append("/// <reference path=\"./importClass-overloads.d.ts\" />\n");
        for (String ref : refs) {
            sb.append("/// <reference path=\"").append(ref).append("\" />\n");
        }
        Files.writeString(new File(outputDir, "index.d.ts").toPath(), sb.toString(), StandardCharsets.UTF_8);
    }
}