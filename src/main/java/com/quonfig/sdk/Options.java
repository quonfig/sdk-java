package com.quonfig.sdk;

import com.quonfig.sdk.eval.ContextSet;
import com.quonfig.sdk.eval.Resolver;
import com.quonfig.sdk.eval.WeightedValueResolver;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable configuration for a {@link Quonfig} client. Build via {@link #builder()}.
 *
 * <p>Env-var fallbacks resolved at {@link Builder#build()}:
 *
 * <ul>
 *   <li>{@code QUONFIG_BACKEND_SDK_KEY} → {@link #sdkKey()}
 *   <li>{@code QUONFIG_DOMAIN} → {@link #domain()}
 *   <li>{@code QUONFIG_ENVIRONMENT} → {@link #environment()}
 * </ul>
 *
 * <p>URL derivation from {@link #domain()} (default {@code quonfig.com}) — explicit {@link
 * Builder#apiUrls(List)} / {@link Builder#telemetryUrl(String)} take precedence:
 *
 * <ul>
 *   <li>{@code https://primary.${domain}}
 *   <li>{@code https://secondary.${domain}}
 *   <li>{@code https://stream.primary.${domain}}
 *   <li>{@code https://stream.secondary.${domain}}
 *   <li>{@code https://telemetry.${domain}}
 * </ul>
 */
public final class Options {

  public static final String DEFAULT_DOMAIN = "quonfig.com";
  public static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(10);

  private final String sdkKey;
  private final String domain;
  private final List<String> apiUrls;
  private final String telemetryUrl;
  private final String environment;
  private final Duration initTimeout;
  private final boolean enablePolling;
  private final Duration pollInterval;
  private final ContextSet globalContext;
  private final Logger logger;
  private final String datadir;
  private final String datafile;
  private final Resolver.EnvLookup envLookup;
  private final Runnable onConfigUpdate;
  private final Consumer<Boolean> onSseConnectionStateChange;
  private final WeightedValueResolver weightedValueResolver;

  private Options(Builder b) {
    Resolver.EnvLookup env = b.envLookup != null ? b.envLookup : Resolver.DEFAULT_ENV_LOOKUP;

    this.envLookup = env;
    this.sdkKey = b.sdkKey != null ? b.sdkKey : env.lookup("QUONFIG_BACKEND_SDK_KEY").orElse(null);
    this.domain = b.domain != null ? b.domain : env.lookup("QUONFIG_DOMAIN").orElse(DEFAULT_DOMAIN);
    this.environment =
        b.environment != null ? b.environment : env.lookup("QUONFIG_ENVIRONMENT").orElse(null);

    String d = this.domain;
    this.apiUrls =
        b.apiUrls != null
            ? List.copyOf(b.apiUrls)
            : List.of("https://primary." + d, "https://secondary." + d);
    this.telemetryUrl = b.telemetryUrl != null ? b.telemetryUrl : "https://telemetry." + d;

    this.initTimeout = b.initTimeout != null ? b.initTimeout : DEFAULT_INIT_TIMEOUT;
    this.enablePolling = b.enablePolling;
    this.pollInterval = b.pollInterval;
    this.globalContext = b.globalContext;
    this.logger = b.logger != null ? b.logger : LoggerFactory.getLogger("com.quonfig.sdk");
    this.datadir = b.datadir;
    this.datafile = b.datafile;
    this.onConfigUpdate = b.onConfigUpdate;
    this.onSseConnectionStateChange = b.onSseConnectionStateChange;
    this.weightedValueResolver = b.weightedValueResolver;
  }

  public String sdkKey() {
    return sdkKey;
  }

  public String domain() {
    return domain;
  }

  public List<String> apiUrls() {
    return apiUrls;
  }

  public String telemetryUrl() {
    return telemetryUrl;
  }

  public String environment() {
    return environment;
  }

  public Duration initTimeout() {
    return initTimeout;
  }

  public boolean enablePolling() {
    return enablePolling;
  }

  public Duration pollInterval() {
    return pollInterval;
  }

  public ContextSet globalContext() {
    return globalContext;
  }

  public Logger logger() {
    return logger;
  }

  public String datadir() {
    return datadir;
  }

  public String datafile() {
    return datafile;
  }

  public Resolver.EnvLookup envLookup() {
    return envLookup;
  }

  public Runnable onConfigUpdate() {
    return onConfigUpdate;
  }

  public Consumer<Boolean> onSseConnectionStateChange() {
    return onSseConnectionStateChange;
  }

  /**
   * Returns the weighted-values bucketing strategy. Null until {@code qfg-oi0j.5} lands the
   * MurmurHash3 implementation; tests may inject a fake to exercise the SPLIT reason path.
   */
  public WeightedValueResolver weightedValueResolver() {
    return weightedValueResolver;
  }

  /**
   * The stream-base URLs derived from the api URLs (each {@code primary.X}/{@code secondary.X} →
   * {@code stream.primary.X}/{@code stream.secondary.X}). Returns the explicit override if {@link
   * Builder#apiUrls(List)} was called.
   */
  public List<String> streamUrls() {
    return apiUrls.stream().map(Options::toStreamUrl).toList();
  }

  private static String toStreamUrl(String apiUrl) {
    int scheme = apiUrl.indexOf("://");
    if (scheme < 0) return apiUrl;
    String prefix = apiUrl.substring(0, scheme + 3);
    String host = apiUrl.substring(scheme + 3);
    return prefix + "stream." + host;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String sdkKey;
    private String domain;
    private List<String> apiUrls;
    private String telemetryUrl;
    private String environment;
    private Duration initTimeout;
    private boolean enablePolling;
    private Duration pollInterval;
    private ContextSet globalContext;
    private Logger logger;
    private String datadir;
    private String datafile;
    private Resolver.EnvLookup envLookup;
    private Runnable onConfigUpdate;
    private Consumer<Boolean> onSseConnectionStateChange;
    private WeightedValueResolver weightedValueResolver;

    public Builder sdkKey(String v) {
      this.sdkKey = v;
      return this;
    }

    public Builder domain(String v) {
      this.domain = v;
      return this;
    }

    public Builder apiUrls(List<String> v) {
      this.apiUrls = Objects.requireNonNull(v, "apiUrls");
      return this;
    }

    public Builder telemetryUrl(String v) {
      this.telemetryUrl = v;
      return this;
    }

    public Builder environment(String v) {
      this.environment = v;
      return this;
    }

    public Builder initTimeout(Duration v) {
      this.initTimeout = v;
      return this;
    }

    public Builder enablePolling(boolean v) {
      this.enablePolling = v;
      return this;
    }

    public Builder pollInterval(Duration v) {
      this.pollInterval = v;
      return this;
    }

    public Builder globalContext(ContextSet v) {
      this.globalContext = v;
      return this;
    }

    public Builder logger(Logger v) {
      this.logger = v;
      return this;
    }

    public Builder datadir(String v) {
      this.datadir = v;
      return this;
    }

    public Builder datafile(String v) {
      this.datafile = v;
      return this;
    }

    public Builder envLookup(Resolver.EnvLookup v) {
      this.envLookup = v;
      return this;
    }

    public Builder onConfigUpdate(Runnable v) {
      this.onConfigUpdate = v;
      return this;
    }

    public Builder onSseConnectionStateChange(Consumer<Boolean> v) {
      this.onSseConnectionStateChange = v;
      return this;
    }

    public Builder weightedValueResolver(WeightedValueResolver v) {
      this.weightedValueResolver = v;
      return this;
    }

    public Options build() {
      return new Options(this);
    }
  }
}
