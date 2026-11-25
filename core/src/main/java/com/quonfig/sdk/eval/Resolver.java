package com.quonfig.sdk.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Post-evaluation value resolution: ENV_VAR provided values, qf1 decryption of secrets, and
 * string→typed coercion for env-var-sourced configs.
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

  private static final ObjectMapper JSON = new ObjectMapper();

  private final EnvLookup envLookup;
  private final Qf1Reader qf1;

  /**
   * @param configStore unused since qf1 secrets replaced {@code decryptWith} key configs; kept so
   *     the constructor signature does not change
   * @param evaluator unused, as {@code configStore}
   * @param envLookup env source for ENV_VAR values and {@code QUONFIG_KEYSET*} keysets; {@code
   *     null} means {@link #DEFAULT_ENV_LOOKUP}
   */
  public Resolver(ConfigStore configStore, Evaluator evaluator, EnvLookup envLookup) {
    this.envLookup = envLookup != null ? envLookup : DEFAULT_ENV_LOOKUP;
    this.qf1 = new Qf1Reader(this.envLookup);
  }

  /**
   * Returns the redacted form of a value for telemetry, or {@link Optional#empty()} if the value is
   * plain (non-confidential and not requiring decryption).
   *
   * <p>The hash is computed over the raw stored string — the qf1 ciphertext for a secret, the
   * plaintext for a plain confidential — so the redaction is stable across evaluations. Callers
   * redact every value of a {@link ConfigType#SECRET} config, confidential flag or not.
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
   * env-var-sourced values; decrypts qf1 secrets (a value of a {@link ConfigType#SECRET} config).
   *
   * <p>A value that still carries {@code decryptWith} (the old secrets format) fails with {@link
   * ResolverException.Kind#UNABLE_TO_DECRYPT}: this SDK no longer reads it and never returns its
   * ciphertext as a value (qf1 spec 13.3 R1).
   */
  public Value resolve(Value val, ConfigRow cfg, String envId, ContextSet contexts) {
    if (val == null) return null;

    if (val.decryptWith() != null && !val.decryptWith().isEmpty()) {
      throw new ResolverException(
          ResolverException.Kind.UNABLE_TO_DECRYPT,
          "value carries decryptWith (the old secrets format), which this SDK no longer reads;"
              + " store it in secrets/ as a qf1 value");
    }
    if (cfg != null && cfg.type() == ConfigType.SECRET) {
      return resolveSecret(val);
    }
    if (val.type() == ValueType.PROVIDED) {
      return resolveProvided(val, cfg);
    }
    return val;
  }

  /**
   * Decrypts a secret's stored qf1 string (spec 8) through the injected env lookup. A failure fails
   * only this read: the message is the qf1 error text (spec 9.2), which never contains key
   * material, the payload or the plaintext, and no cause is chained.
   */
  private Value resolveSecret(Value val) {
    Object payload = val.value();
    if (!(payload instanceof String)) {
      throw new ResolverException(
          ResolverException.Kind.UNABLE_TO_DECRYPT,
          "qf1 malformed: a secret value must be a qf1 string");
    }
    try {
      return new Value(ValueType.STRING, qf1.decrypt((String) payload), true, null);
    } catch (Qf1Reader.Qf1Exception e) {
      throw new ResolverException(ResolverException.Kind.UNABLE_TO_DECRYPT, e.getMessage());
    }
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

    return new Value(
        cfg.valueType(), coerce(envValue.get(), pv.lookup(), cfg.valueType(), cfg.key()));
  }

  private static Object coerce(
      String envValue, String envVarName, ValueType valueType, String configKey) {
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
        case STRING_LIST:
          return splitStringList(envValue);
        case JSON:
          return JSON.readValue(envValue, Object.class);
        case DURATION:
          // Validated here so a malformed env var fails at resolution like any other uncoercible
          // value; the ISO text is kept as the payload, as a stored duration is (sdk-go parity), so
          // the untyped evaluateDetails read returns it unchanged. The typed getter parses it.
          Duration.parse(envValue);
          return envValue;
        case STRING:
        case LOG_LEVEL:
        default:
          return envValue;
      }
    } catch (IllegalArgumentException | DateTimeParseException | JsonProcessingException e) {
      // Never include the env value, or the parser's message (which quotes it), and do not chain
      // the parser exception as the cause: the value may be a secret (qfg-goi1.2.32).
      throw new ResolverException(
          ResolverException.Kind.UNABLE_TO_COERCE,
          "environment variable \""
              + envVarName
              + "\" cannot be converted to "
              + valueType.name().toLowerCase(Locale.ROOT)
              + " for config \""
              + configKey
              + "\"");
    }
  }

  private static List<String> splitStringList(String envValue) {
    if (envValue.isEmpty()) return List.of();
    return Arrays.stream(envValue.split(",", -1))
        .map(String::trim)
        .collect(Collectors.toUnmodifiableList());
  }

  private static String stringOf(Object value) {
    if (value == null) return "";
    if (value instanceof String) return (String) value;
    return String.valueOf(value);
  }
}
