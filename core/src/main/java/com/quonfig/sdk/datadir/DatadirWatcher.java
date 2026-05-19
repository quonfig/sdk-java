package com.quonfig.sdk.datadir;

import com.sun.nio.file.SensitivityWatchEventModifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Watches a datadir workspace for filesystem changes and fires {@code onChange} once per debounced
 * burst. Uses {@link java.nio.file.WatchService} so there is no third-party dependency: native
 * inotify on Linux, ReadDirectoryChangesW on Windows, and a polling fallback on macOS (HIGH
 * sensitivity = ~2s detection floor). Registration failures (missing path, read-only fs, immutable
 * container) are reported via {@code onError}; in that case {@link #start()} returns {@code false}
 * and no watcher state is held.
 *
 * <p>The caller owns parse-then-swap: this class only fires the debounced trigger.
 *
 * <p>Mirrors {@code sdk-node/src/datadirWatcher.ts} (qfg-mol-0kr) — same API shape: {@link
 * #start()} → boolean, {@link #close()} is idempotent, debounced bursts coalesce into a single
 * {@code onChange} per burst. Resolves symlinks at start so atomic flips of the link itself are not
 * detected; the common ask is "edit the file the link points at, see updates."
 */
public final class DatadirWatcher implements AutoCloseable {

  private final Path datadir;
  private final long debounceMs;
  private final Runnable onChange;
  private final Consumer<Throwable> onError;
  private final ConcurrentHashMap<WatchKey, Path> watchedDirs = new ConcurrentHashMap<>();
  private final Object scheduleLock = new Object();

  private volatile WatchService watchService;
  private volatile Thread watchThread;
  private volatile ScheduledExecutorService debouncer;
  private volatile ScheduledFuture<?> pendingDebounce;
  private volatile boolean closed;

  public DatadirWatcher(
      Path datadir, long debounceMs, Runnable onChange, Consumer<Throwable> onError) {
    this.datadir = Objects.requireNonNull(datadir, "datadir");
    this.debounceMs = debounceMs;
    this.onChange = Objects.requireNonNull(onChange, "onChange");
    this.onError = Objects.requireNonNull(onError, "onError");
  }

  /**
   * Resolves the datadir to its real path (symlink-following), opens a {@link WatchService},
   * registers every existing subdirectory, and starts a daemon thread that translates filesystem
   * events into debounced {@code onChange} calls. Returns {@code false} on any registration failure
   * — typical causes: the datadir does not exist, lives on a read-only filesystem, or runs inside
   * an immutable container. On failure, {@code onError} is invoked with the cause and the watcher
   * cleans up any partially-built state so {@link #close()} is still safe to call.
   */
  public boolean start() {
    WatchService ws = null;
    ScheduledExecutorService exec = null;
    try {
      Path resolved = datadir.toRealPath();
      ws = FileSystems.getDefault().newWatchService();
      this.watchService = ws;
      registerRecursively(resolved);
      exec =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "quonfig-datadir-debouncer");
                t.setDaemon(true);
                return t;
              });
      this.debouncer = exec;
      Thread t = new Thread(this::eventLoop, "quonfig-datadir-watcher");
      t.setDaemon(true);
      t.start();
      this.watchThread = t;
      return true;
    } catch (IOException | RuntimeException e) {
      onError.accept(e);
      if (ws != null) {
        try {
          ws.close();
        } catch (IOException ignored) {
          // best-effort cleanup
        }
      }
      this.watchService = null;
      if (exec != null) exec.shutdownNow();
      this.debouncer = null;
      watchedDirs.clear();
      return false;
    }
  }

  private void registerRecursively(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(Files::isDirectory).forEach(this::registerDir);
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  private void registerDir(Path dir) {
    try {
      WatchKey key =
          dir.register(
              watchService,
              new WatchEvent.Kind<?>[] {
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY
              },
              SensitivityWatchEventModifier.HIGH);
      watchedDirs.put(key, dir);
    } catch (IOException e) {
      throw new UncheckedIOException("register " + dir, e);
    }
  }

  private void eventLoop() {
    while (!closed) {
      WatchKey key;
      try {
        key = watchService.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (ClosedWatchServiceException e) {
        return;
      }
      Path dir = watchedDirs.get(key);
      for (WatchEvent<?> ev : key.pollEvents()) {
        if (ev.kind() == StandardWatchEventKinds.ENTRY_CREATE && dir != null) {
          Path child = dir.resolve((Path) ev.context());
          if (Files.isDirectory(child)) {
            try {
              registerDir(child);
            } catch (UncheckedIOException ignored) {
              // best-effort — a transient failure to register a new subdir is not fatal
            }
          }
        }
      }
      scheduleDebounce();
      if (!key.reset()) {
        watchedDirs.remove(key);
      }
    }
  }

  private void scheduleDebounce() {
    synchronized (scheduleLock) {
      if (closed) return;
      ScheduledExecutorService exec = debouncer;
      if (exec == null) return;
      ScheduledFuture<?> p = pendingDebounce;
      if (p != null) p.cancel(false);
      pendingDebounce = exec.schedule(this::fire, debounceMs, TimeUnit.MILLISECONDS);
    }
  }

  private void fire() {
    if (closed) return;
    try {
      onChange.run();
    } catch (RuntimeException e) {
      onError.accept(e);
    }
  }

  @Override
  public void close() {
    closed = true;
    synchronized (scheduleLock) {
      ScheduledFuture<?> p = pendingDebounce;
      if (p != null) p.cancel(false);
      pendingDebounce = null;
    }
    WatchService ws = watchService;
    if (ws != null) {
      try {
        ws.close();
      } catch (IOException ignored) {
        // caller already in shutdown — best effort
      }
      watchService = null;
    }
    ScheduledExecutorService exec = debouncer;
    if (exec != null) exec.shutdownNow();
    debouncer = null;
    Thread t = watchThread;
    if (t != null) {
      try {
        t.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      watchThread = null;
    }
    watchedDirs.clear();
  }
}
