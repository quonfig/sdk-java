package com.quonfig.sdk.telemetry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manual time for the telemetry transport contract (the contract's {@code advance(ms)}). One
 * virtual clock read by both {@link #clock()} and this {@link ScheduledExecutorService}; injected
 * through {@code Options.Builder#telemetryClock} / {@code #telemetryScheduler}, so only the SDK's
 * telemetry timers move. Due tasks run inline on the thread calling {@link #advance}, in time order
 * (ties in scheduling order). After each task the {@code settle} hook runs, so real HTTP I/O a task
 * started can finish before virtual time moves on.
 */
final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {

  private final class Task implements ScheduledFuture<Object> {
    final long at;
    final long seq;
    final Runnable fn;
    volatile boolean cancelled;
    volatile boolean done;

    Task(long at, long seq, Runnable fn) {
      this.at = at;
      this.seq = seq;
      this.fn = fn;
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(at - now(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
      return Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
    }

    @Override
    public boolean cancel(boolean mayInterrupt) {
      if (done) return false;
      cancelled = true;
      synchronized (ManualScheduler.this) {
        tasks.remove(this);
      }
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public boolean isDone() {
      return done || cancelled;
    }

    @Override
    public Object get() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }
  }

  private final List<Task> tasks = new ArrayList<>();
  private volatile long now;
  private long seq;
  private volatile Runnable settle = () -> {};
  private volatile boolean shutdown;

  ManualScheduler() {
    this.now = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli();
  }

  long now() {
    return now;
  }

  void onSettle(Runnable r) {
    this.settle = r;
  }

  /** Tasks scheduled and not yet fired or cancelled. */
  synchronized int pending() {
    return tasks.size();
  }

  Clock clock() {
    return new Clock() {
      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        return Instant.ofEpochMilli(now);
      }

      @Override
      public long millis() {
        return now;
      }
    };
  }

  /** Move time forward by {@code ms}, firing due tasks in time order. */
  void advance(long ms) {
    long target = now + ms;
    settle.run();
    for (; ; ) {
      Task next = null;
      synchronized (this) {
        for (Task t : tasks) {
          if (t.at <= target && (next == null || t.at < next.at)) next = t;
        }
        if (next != null) {
          tasks.remove(next);
          now = next.at;
        }
      }
      if (next == null) break;
      next.done = true;
      next.fn.run();
      settle.run();
    }
    now = target;
    settle.run();
  }

  @Override
  public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    Task t = new Task(now + Math.max(0, unit.toMillis(delay)), seq++, command);
    tasks.add(t);
    return t;
  }

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      Runnable command, long initialDelay, long period, TimeUnit unit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable command, long initialDelay, long delay, TimeUnit unit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void execute(Runnable command) {
    command.run();
  }

  @Override
  public void shutdown() {
    shutdown = true;
  }

  @Override
  public List<Runnable> shutdownNow() {
    shutdown = true;
    return List.of();
  }

  @Override
  public boolean isShutdown() {
    return shutdown;
  }

  @Override
  public boolean isTerminated() {
    return shutdown;
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) {
    return true;
  }

  @Override
  public <T> List<java.util.concurrent.Future<T>> invokeAll(
      Collection<? extends Callable<T>> tasks) {
    throw new UnsupportedOperationException();
  }
}
