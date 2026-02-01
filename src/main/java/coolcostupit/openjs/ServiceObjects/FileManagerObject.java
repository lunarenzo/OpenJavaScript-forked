/*
 * Copyright (c) 2026 coolcostupit
 * Licensed under AGPL-3.0
 * You may not remove this notice or claim this work as your own.
 */

package coolcostupit.openjs.ServiceObjects;

import coolcostupit.openjs.logging.pluginLogger;
import coolcostupit.openjs.modules.FoliaSupport;
import coolcostupit.openjs.modules.scriptManager;
import coolcostupit.openjs.modules.sharedClass;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

public class FileManagerObject {

    private WatchService watchService;
    private Thread watcherThread;
    private boolean watcherStarted = false;
    private final ScriptEngine Engine;
    private final ScriptClassObject scriptClass;
    private final List<FileListenerHandle> listeners = new CopyOnWriteArrayList<>();

    public FileManagerObject(ScriptClassObject scriptClass, ScriptEngine scriptEngine) {
        this.scriptClass = scriptClass;
        this.Engine = scriptEngine;
    }

    public class FileListenerHandle {
        private final WatchKey key;
        private final Object handler;
        private boolean active = true;
        private final Path path;
        private final WatchEvent.Kind<?> kind;

        private FileListenerHandle(WatchKey key, Object handler, WatchEvent.Kind<?> kind, Path path) {
            this.key = key;
            this.handler = handler;
            this.path = path;
            this.kind = kind; // German: NEIN NEIN NEIN!!!
        }

        public void disconnect() {
            if (!active) return;
            active = false;
            key.cancel();
        }

        private void dispatch(WatchEvent<?> event) {
            if (!active) return;

            try {
                ((Invocable) Engine).invokeMethod(handler, "e", path.resolve((Path) event.context()).toFile(), event.kind().name());
            } catch (Exception e) {
                sharedClass.logger.scriptlog(Level.SEVERE, scriptClass.RelativePath, "Error during file event: "+e.getMessage(), pluginLogger.ORANGE);
            }
        }
    }


    public String readFile(String relativePath) {
        File target = resolveTargetFile(relativePath);
        if (target == null || !target.exists()) return null;

        try {
            return Files.readString(target.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    public boolean fileExists(String relativePath) {
        File target = resolveTargetFile(relativePath);
        return target != null && target.exists();
    }

    public Map<String, Object> writeToFile(String relativePath, String data) {
        Map<String, Object> result = new HashMap<>();
        File target = resolveTargetFile(relativePath);

        if (target == null) {
            result.put("success", false);
            result.put("error", "Invalid path");
            return result;
        }

        try {
            Files.createDirectories(target.getParentFile().toPath());
            Files.writeString(target.toPath(), data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            result.put("success", true);
        } catch (IOException e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    public File createFile(String relativePath) {
        File target = resolveTargetFile(relativePath);
        if (target == null) return null;

        try {
            Files.createDirectories(target.getParentFile().toPath());
            if (!target.exists()) {
                Files.createFile(target.toPath());
            }
            return target;
        } catch (IOException e) {
            return null;
        }
    }

    public Map<String, Object> createFolder(String relativePath) {
        Map<String, Object> result = new HashMap<>();
        File target = resolveTargetFile(relativePath);

        if (target == null) {
            result.put("success", false);
            result.put("error", "Invalid path");
            return result;
        }

        try {
            if (!target.exists()) {
                Files.createDirectories(target.toPath());
            }
            result.put("success", true);
        } catch (IOException e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    public FileListenerHandle listenOnPath(String relativePath, String listenerType, Object handler) {
        try {
            WatchEvent.Kind<?> kind = switch (listenerType) {
                case "ENTRY_CREATE" -> StandardWatchEventKinds.ENTRY_CREATE;
                case "ENTRY_DELETE" -> StandardWatchEventKinds.ENTRY_DELETE;
                case "ENTRY_MODIFY" -> StandardWatchEventKinds.ENTRY_MODIFY;
                default -> null;
            };

            if (kind == null) return null;

            File base = scriptClass.File.getParentFile();
            File target = new File(base, relativePath);
            Path path = target.toPath();

            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }

            if (!watcherStarted) {
                startWatcherThread();
            }

            WatchKey key = path.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
            );
            FileListenerHandle handle = new FileListenerHandle(key, handler, kind, target.toPath());

            listeners.add(handle);
            return handle;
        } catch (Exception e) {
            return null;
        }
    }

    public void clearFileListeners() {
        if (watcherStarted) {
            watcherStarted = false;
            watcherThread.interrupt();
            try {
                watchService.close();
            } catch (IOException ignored) {}
        }
    }

    private void startWatcherThread() throws IOException {
        if (watcherStarted) return;

        watchService = FileSystems.getDefault().newWatchService();
        watcherStarted = true;

        Thread watcher = new Thread(() -> {
            while (watcherStarted) {
                try {
                    WatchKey key;

                    try {
                        key = watchService.take();
                    } catch (Exception ignored) {
                        break;
                    }

                    List<WatchEvent<?>> events = key.pollEvents();

                    for (FileListenerHandle listener : listeners) {
                        if (listener.key == key) {
                            for (WatchEvent<?> event : events) {
                                if (event.kind() == listener.kind) {
                                    FoliaSupport.runTask(sharedClass.plugin, () -> listener.dispatch(event)); // lambda (OMG HALF LIFE 3????)
                                }
                            }
                        }
                    }

                    if (!key.reset()) {
                        listeners.removeIf(l -> l.key == key);
                    }
                } catch (Exception e) {
                    if (!watcherStarted) { // intentional shutdown
                        break;
                    }
                    sharedClass.logger.logScriptError("File watcher interrupted unexpectedly: ", scriptClass.Name);
                    sharedClass.logger.logScriptError(e, scriptClass.getRelativePath());
                    break;
                }
            }
        });

        watcher.setDaemon(true);
        watcher.start();
        watcherThread = watcher;
    }

    private File resolveTargetFile(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;

        File base = scriptClass.File.getParentFile();
        return new File(base, relativePath.replace('\\', '/'));
    }

    public File getFile(String relativePath) {
        File target = resolveTargetFile(relativePath);
        return (target != null && target.exists() && target.isFile()) ? target : null;
    }

    public boolean removeFile(String relativePath) {
        File target = resolveTargetFile(relativePath);
        if (target == null || !target.exists() || !target.isFile()) return false;

        try {
            return Files.deleteIfExists(target.toPath());
        } catch (IOException e) {
            return false;
        }
    }

    public File getFolder(String relativePath) {
        File target = resolveTargetFile(relativePath);
        return (target != null && target.exists() && target.isDirectory()) ? target : null;
    }

    public boolean removeFolder(String relativePath) {
        File target = resolveTargetFile(relativePath);
        if (target == null || !target.exists() || !target.isDirectory()) return false;

        try {
            boolean deleted = false;
            Files.walk(target.toPath())
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {}
                    });
            try {
                deleted = target.delete();
            } catch (Exception ignored) {};
            
            return deleted;
        } catch (IOException e) {
            return false;
        }
    }

    public List<File> getFiles(String folderPath) {
        File target = resolveTargetFile(folderPath);
        if (target == null || !target.exists() || !target.isDirectory()) {
            return Collections.emptyList();
        }

        File[] files = target.listFiles();
        return files != null ? Arrays.asList(files) : Collections.emptyList();
    }
}
