package com.quonfig.sdk.telemetry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Scriptable in-process telemetry endpoint for the telemetry transport contract
 * (integration-test-data/chaos/telemetry-transport-contract.md). A real JDK {@link HttpServer} on
 * 127.0.0.1:0. Each received POST is recorded (raw body bytes) and answered by the next scripted
 * step, else the default step (200). A hang step holds the response until {@link #release} or
 * {@link #close}.
 */
final class TelemetryStub implements AutoCloseable {

  /** One scripted answer: a status (with optional Retry-After and body), or a hang. */
  static final class Step {
    final int status;
    final String retryAfter;
    final String body;
    final boolean hang;

    private Step(int status, String retryAfter, String body, boolean hang) {
      this.status = status;
      this.retryAfter = retryAfter;
      this.body = body;
      this.hang = hang;
    }

    static Step status(int status) {
      return new Step(status, null, "{}", false);
    }

    static Step status(int status, String retryAfter) {
      return new Step(status, retryAfter, "{}", false);
    }

    static Step withBody(int status, String body) {
      return new Step(status, null, body, false);
    }

    static Step hang() {
      return new Step(0, null, null, true);
    }
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool(daemon());
  private final List<byte[]> bodies = new ArrayList<>();
  private final ArrayDeque<Step> script = new ArrayDeque<>();
  private final Map<Integer, CompletableFuture<Step>> held = new ConcurrentHashMap<>();
  private volatile Step defaultStep = Step.status(200);

  TelemetryStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  synchronized void script(Step... steps) {
    for (Step s : steps) script.addLast(s);
  }

  void setDefault(Step step) {
    this.defaultStep = step;
  }

  synchronized int postCount() {
    return bodies.size();
  }

  synchronized byte[] body(int i) {
    return bodies.get(i);
  }

  String text(int i) {
    return new String(body(i), StandardCharsets.UTF_8);
  }

  String sha(int i) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body(i)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Whether POST {@code i} is currently held by a hang step. */
  boolean isHeld(int i) {
    return held.containsKey(i);
  }

  /** Wait (real time, up to 5s) until the stub has received {@code n} POSTs. */
  void waitForPosts(int n) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (postCount() < n) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("stub saw " + postCount() + " POSTs, want " + n);
      }
      Thread.sleep(2);
    }
  }

  /** Answer the held POST {@code i} with {@code step}. */
  void release(int i, Step step) {
    CompletableFuture<Step> f = held.remove(i);
    if (f == null) throw new IllegalStateException("POST " + i + " is not held");
    f.complete(step);
  }

  @Override
  public void close() {
    for (CompletableFuture<Step> f : held.values()) f.complete(Step.status(503));
    held.clear();
    server.stop(0);
    executor.shutdownNow();
  }

  private void handle(HttpExchange ex) throws IOException {
    byte[] body = ex.getRequestBody().readAllBytes();
    int i;
    Step step;
    CompletableFuture<Step> hold = null;
    synchronized (this) {
      i = bodies.size();
      bodies.add(body);
      step = script.isEmpty() ? defaultStep : script.pollFirst();
      if (step.hang) {
        hold = new CompletableFuture<>();
        held.put(i, hold);
      }
    }
    if (hold != null) {
      try {
        step = hold.get();
      } catch (Exception e) {
        ex.close();
        return;
      }
    }
    answer(ex, step);
  }

  private static void answer(HttpExchange ex, Step step) throws IOException {
    try {
      byte[] out = (step.body == null ? "{}" : step.body).getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().set("Content-Type", "application/json");
      if (step.retryAfter != null) ex.getResponseHeaders().set("Retry-After", step.retryAfter);
      ex.sendResponseHeaders(step.status, out.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(out);
      }
    } catch (IOException e) {
      // The client aborted (timeout / close()); nothing to answer.
    } finally {
      ex.close();
    }
  }

  private static java.util.concurrent.ThreadFactory daemon() {
    return r -> {
      Thread t = new Thread(r, "telemetry-stub");
      t.setDaemon(true);
      return t;
    };
  }
}
