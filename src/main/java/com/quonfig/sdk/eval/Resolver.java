package com.quonfig.sdk.eval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Post-evaluation value resolution: ENV_VAR provided values, AES-GCM decryption of confidential
 * values, and string→typed coercion for env-var-sourced configs.
 *
 * <p>Mirrors the contract in {@code sdk-go/internal/resolver}. Plain (non-provided,
 * non-confidential) values pass through unchanged.
 */
public final class Resolver {

  /** Prefix used by telemetry when redacting confidential values. */
  public static final String REPORTABLE_VALUE_PREFIX = "*****";

  /** Pluggable env-var source so callers can inject a fake in tests. */
  @FunctionalInterface
  public interface EnvLookup {
    Optional<String> lookup(String key);
  }

  /** Reads from the JVM's environment via {@link System#getenv(String)}. */
  public static final EnvLookup DEFAULT_ENV_LOOKUP = key -> Optional.ofNullable(System.getenv(key));

  private final ConfigStore configStore;
  private final Evaluator evaluator;
  private final EnvLookup envLookup;

  public Resolver(ConfigStore configStore, Evaluator evaluator, EnvLookup envLookup) {
    this.configStore = configStore;
    this.evaluator = evaluator;
    this.envLookup = envLookup != null ? envLookup : DEFAULT_ENV_LOOKUP;
  }

  /**
   * Returns the redacted form of a value for telemetry, or {@link Optional#empty()} if the value is
   * plain (non-confidential and not requiring decryption).
   *
   * <p>The hash is computed over the raw stored string — ciphertext for {@code decryptWith} values,
   * plaintext for plain confidentials — so the redaction is stable across evaluations.
   */
  public static Optional<String> reportableValueFor(Value val) {
    if (val == null) return Optional.empty();
    if (!val.confidential() && (val.decryptWith() == null || val.decryptWith().isEmpty())) {
      return Optional.empty();
    }
    String raw = stringOf(val.value());
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] sum = md.digest(raw.getBytes(StandardCharsets.UTF_8));
      String hex = HexFormat.of().formatHex(sum);
      if (hex.length() < 5) return Optional.empty();
      return Optional.of(REPORTABLE_VALUE_PREFIX + hex.substring(0, 5));
    } catch (NoSuchAlgorithmException e) {
      return Optional.empty();
    }
  }

  /**
   * Resolves a value to its final form. Returns the input unchanged for plain values; materializes
   * env-var-sourced values; decrypts AES-GCM confidentials.
   */
  public Value resolve(Value val, ConfigRow cfg, String envId, ContextSet contexts) {
    if (val == null) return null;

    if (val.type() == ValueType.PROVIDED) {
      return resolveProvided(val, cfg);
    }
    if (val.confidential() && val.decryptWith() != null && !val.decryptWith().isEmpty()) {
      return resolveDecryption(val, cfg, envId, contexts);
    }
    return val;
  }

  private Value resolveProvided(Value val, ConfigRow cfg) {
    Object payload = val.value();
    if (!(payload instanceof ProvidedValue)) {
      return val;
    }
    ProvidedValue pv = (ProvidedValue) payload;
    if (!"ENV_VAR".equals(pv.source())) {
      return val;
    }

    Optional<String> envValue = envLookup.lookup(pv.lookup());
    if (envValue.isEmpty()) {
      throw new ResolverException(
          ResolverException.Kind.MISSING_ENV_VAR,
          "environment variable \"" + pv.lookup() + "\" not set for config \"" + cfg.key() + "\"");
    }

    return new Value(cfg.valueType(), coerce(envValue.get(), cfg.valueType(), cfg.key()));
  }

  private Value resolveDecryption(Value val, ConfigRow cfg, String envId, ContextSet contexts) {
    if (configStore == null || evaluator == null) {
      throw new ResolverException(
          ResolverException.Kind.UNABLE_TO_DECRYPT,
          "no config store available for decryption key lookup");
    }

    ConfigRow keyCfg = configStore.getConfig(val.decryptWith());
    if (keyCfg == null) {
      throw new ResolverException(
          ResolverException.Kind.UNABLE_TO_DECRYPT,
          "decryption key config \"" + val.decryptWith() + "\" not found");
    }

    EvaluationMatch keyMatch = evaluator.evaluate(keyCfg, envId, contexts);
    if (!keyMatch.isMatch() || keyMatch.value() == null) {
      throw new ResolverException(
          ResolverException.Kind.UNABLE_TO_DECRYPT,
          "decryption key config \"" + val.decryptWith() + "\" did not match");
    }

    Value resolvedKey;
    try {
      resolvedKey = resolve(keyMatch.value(), keyCfg, envId, contexts);
    } catch (ResolverException e) {
      throw new ResolverException(
          ResolverException.Kind.UNABLE_TO_DECRYPT,
          "failed to resolve decryption key from \"" + val.decryptWith() + "\": " + e.getMessage(),
          e);
    }

    String secretKeyHex = stringOf(resolvedKey.value());
    if (secretKeyHex.isEmpty()) {
      throw new ResolverException(
          ResolverException.Kind.UNABLE_TO_DECRYPT,
          "decryption key from \"" + val.decryptWith() + "\" is empty");
    }

    String ciphertext = stringOf(val.value());
    String decrypted;
    try {
      decrypted = Encryption.decrypt(secretKeyHex, ciphertext);
    } catch (Exception e) {
      throw new ResolverException(
          ResolverException.Kind.UNABLE_TO_DECRYPT,
          "decryption failed for config \"" + cfg.key() + "\": " + e.getMessage(),
          e);
    }

    return new Value(ValueType.STRING, decrypted, true, null);
  }

  private static Object coerce(String envValue, ValueType valueType, String configKey) {
    try {
      switch (valueType) {
        case INT:
          return Long.parseLong(envValue);
        case DOUBLE:
          return Double.parseDouble(envValue);
        case BOOL:
          if ("true".equalsIgnoreCase(envValue) || "1".equals(envValue)) return Boolean.TRUE;
          if ("false".equalsIgnoreCase(envValue) || "0".equals(envValue)) return Boolean.FALSE;
          throw new IllegalArgumentException("not a boolean");
        case STRING:
        case STRING_LIST:
        case LOG_LEVEL:
        case DURATION:
        case JSON:
        default:
          return envValue;
      }
    } catch (IllegalArgumentException e) {
      throw new ResolverException(
          ResolverException.Kind.UNABLE_TO_COERCE,
          "cannot convert \""
              + envValue
              + "\" to "
              + valueType
              + " for config \""
              + configKey
              + "\": "
              + e.getMessage(),
          e);
    }
  }

  private static String stringOf(Object value) {
    if (value == null) return "";
    if (value instanceof String) return (String) value;
    return String.valueOf(value);
  }
}
