package com.quonfig.sdk.datadir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the opt-in {@code dataDirAutoReload} pathway (qfg-mol-3jq). The WatchService on macOS is
 * polling-based; HIGH sensitivity gives a ~2s detection floor, so each assertion uses a generous
 * 12s timeout while the negative tests must out-wait that polling interval before claiming "nothing
 * fired".
 */
class DatadirAutoReloadTest {

  private static final long WAIT_TIMEOUT_MS = 12_000;
  // > macOS polling sensitivity (HIGH = 2s) + debounce window. Negative assertions ("no callback
  // fired") must out-wait this or they're not actually exercising the watcher.
  private static final long NEGATIVE_WAIT_MS = 4_500;

  @TempDir Path workspace;

  private void seedWorkspace(String greeting) throws Exception {
    Files.writeString(
        workspace.resolve("quonfig.json"),
        "{\"workspace\":\"test\",\"environments\":[\"production\"]}");
    Files.createDirectories(workspace.resolve("configs"));
    writeGreeting(greeting);
  }

  private void writeGreeting(String value) throws Exception {
    String json =
        "{\"id\":\"welcome\",\"key\":\"welcome\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],"
            + "\"value\":{\"type\":\"string\",\"value\":\""
            + value
            + "\"}}]}}";
    Files.writeString(workspace.resolve("configs").resolve("welcome.json"), json);
  }

  @Test
  void happyPath_reloadsEnvelopeAndFiresCallback() throws Exception {
    seedWorkspace("hola");
    AtomicInteger callbacks = new AtomicInteger(0);
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(workspace.toString())
                .environment("production")
                .dataDirAutoReload(true)
                .dataDirAutoReloadDebounceMs(80)
                .onConfigUpdate(callbacks::incrementAndGet)
                .disableTelemetry(true)
                .build())) {
      assertEquals("hola", q.getString("welcome", "fallback"));
      int initial = callbacks.get();

      writeGreeting("buenos-dias");

      waitFor(() -> "buenos-dias".equals(q.getString("welcome", "fallback")), WAIT_TIMEOUT_MS);
      assertTrue(
          callbacks.get() > initial, "onConfigUpdate should have fired after the file changed");
    }
  }

  @Test
  void disabledByDefault_noReloadAfterFileChange() throws Exception {
    seedWorkspace("hola");
    AtomicInteger callbacks = new AtomicInteger(0);
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(workspace.toString())
                .environment("production")
                .onConfigUpdate(callbacks::incrementAndGet)
                .disableTelemetry(true)
                .build())) {
      int initial = callbacks.get();
      writeGreeting("changed");
      Thread.sleep(NEGATIVE_WAIT_MS);
      assertEquals("hola", q.getString("welcome", "fallback"));
      assertEquals(initial, callbacks.get(), "no extra callback when dataDirAutoReload is off");
    }
  }

  @Test
  void debounces_burstOfWritesProducesSingleCallback() throws Exception {
    seedWorkspace("v0");
    AtomicInteger postInit = new AtomicInteger(0);
    AtomicInteger initialized = new AtomicInteger(0);
    Runnable onUpdate =
        () -> {
          if (initialized.get() == 1) postInit.incrementAndGet();
        };
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(workspace.toString())
                .environment("production")
                .dataDirAutoReload(true)
                .dataDirAutoReloadDebounceMs(500)
                .onConfigUpdate(onUpdate)
                .disableTelemetry(true)
                .build())) {
      initialized.set(1);

      for (int i = 1; i <= 5; i++) {
        writeGreeting("v" + i);
        Thread.sleep(10);
      }

      waitFor(() -> "v5".equals(q.getString("welcome", "fallback")), WAIT_TIMEOUT_MS);
      // Allow any straggler debounce timer to flush before snapshotting the count.
      Thread.sleep(1500);

      assertEquals(
          1, postInit.get(), "burst of writes should coalesce into a single debounced reload");
    }
  }

  @Test
  void parseError_keepsPreviousEnvelope_andDoesNotFireCallback() throws Exception {
    seedWorkspace("hola");
    AtomicInteger postInit = new AtomicInteger(0);
    AtomicInteger initialized = new AtomicInteger(0);
    Runnable onUpdate =
        () -> {
          if (initialized.get() == 1) postInit.incrementAndGet();
        };
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(workspace.toString())
                .environment("production")
                .dataDirAutoReload(true)
                .dataDirAutoReloadDebounceMs(80)
                .onConfigUpdate(onUpdate)
                .disableTelemetry(true)
                .build())) {
      initialized.set(1);

      Files.writeString(workspace.resolve("configs").resolve("welcome.json"), "{not json");
      Thread.sleep(NEGATIVE_WAIT_MS);

      assertEquals("hola", q.getString("welcome", "fallback"));
      assertEquals(
          0, postInit.get(), "parse failure must keep prior envelope and skip the callback");
    }
  }

  @Test
  void close_stopsWatcher_noCallbacksAfterClose() throws Exception {
    seedWorkspace("hola");
    AtomicInteger postClose = new AtomicInteger(0);
    AtomicInteger isClosed = new AtomicInteger(0);
    Runnable onUpdate =
        () -> {
          if (isClosed.get() == 1) postClose.incrementAndGet();
        };
    Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(workspace.toString())
                .environment("production")
                .dataDirAutoReload(true)
                .dataDirAutoReloadDebounceMs(80)
                .onConfigUpdate(onUpdate)
                .disableTelemetry(true)
                .build());
    q.close();
    isClosed.set(1);

    writeGreeting("after-close");
    Thread.sleep(NEGATIVE_WAIT_MS);

    assertEquals(0, postClose.get(), "close() must stop the watcher so no further callbacks fire");
  }

  @Test
  void registrationFailure_returnsFalse_andCallsOnError() {
    Path missing = workspace.resolve("does-not-exist");
    AtomicInteger errors = new AtomicInteger(0);
    DatadirWatcher watcher =
        new DatadirWatcher(
            missing,
            10,
            () -> {
              throw new AssertionError("onChange should not fire");
            },
            err -> errors.incrementAndGet());
    try {
      assertFalse(watcher.start(), "start() must return false when the target path is missing");
      assertTrue(errors.get() > 0, "onError must be invoked when registration fails");
    } finally {
      watcher.close();
    }
  }

  @Test
  void symlinkedDatadir_followsResolvedRealPath() throws Exception {
    seedWorkspace("hola");
    Path linkParent = Files.createTempDirectory("quonfig-sdk-java-watch-symlink-");
    Path link = linkParent.resolve("workspace-link");
    try {
      Files.createSymbolicLink(link, workspace);
      AtomicInteger callbacks = new AtomicInteger(0);
      try (Quonfig q =
          new Quonfig(
              Options.builder()
                  .datadir(link.toString())
                  .environment("production")
                  .dataDirAutoReload(true)
                  .dataDirAutoReloadDebounceMs(80)
                  .onConfigUpdate(callbacks::incrementAndGet)
                  .disableTelemetry(true)
                  .build())) {
        int initial = callbacks.get();
        writeGreeting("via-symlink");
        waitFor(() -> "via-symlink".equals(q.getString("welcome", "fallback")), WAIT_TIMEOUT_MS);
        assertTrue(callbacks.get() > initial);
      }
    } finally {
      Files.deleteIfExists(link);
      Files.deleteIfExists(linkParent);
    }
  }

  private static void waitFor(BooleanSupplier predicate, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (System.nanoTime() < deadline) {
      if (predicate.getAsBoolean()) return;
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out after " + timeoutMs + "ms");
  }
}
