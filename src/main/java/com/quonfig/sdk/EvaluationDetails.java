package com.quonfig.sdk;

import java.util.Map;
import java.util.Objects;

/**
 * The full outcome of a single typed evaluation: the value the caller will use, the reason it was
 * chosen, and metadata that lets a downstream consumer (e.g. an OpenFeature provider) build a
 * complete {@code EvaluationDetails} of its own.
 *
 * <p>Field semantics:
 *
 * <ul>
 *   <li>{@link #value()} — the typed value returned to the caller. On {@link Reason#ERROR} this is
 *       the caller's default; on {@link Reason#DEFAULT} this is also the caller's default; on
 *       STATIC / TARGETING_MATCH / SPLIT this is the resolved config value.
 *   <li>{@link #reason()} — never null.
 *   <li>{@link #variantIndex()} — populated only when {@link #reason()} is {@link Reason#SPLIT};
 *       null otherwise.
 *   <li>{@link #errorCode()} — populated only when {@link #reason()} is {@link Reason#ERROR}; null
 *       otherwise.
 *   <li>{@link #errorMessage()} — companion to {@link #errorCode()}; may be null.
 *   <li>{@link #metadata()} — never null. Standard keys: {@code configId}, {@code configKey},
 *       {@code configType}, {@code ruleIndex}, {@code weightedValueIndex} (when applicable), {@code
 *       environment}.
 * </ul>
 *
 * @param <T> the typed value's Java type (String, Boolean, Long, Double, List&lt;String&gt;, …).
 */
public final class EvaluationDetails<T> {
  private final T value;
  private final Reason reason;
  private final Integer variantIndex;
  private final ErrorCode errorCode;
  private final String errorMessage;
  private final Map<String, Object> metadata;

  public EvaluationDetails(
      T value,
      Reason reason,
      Integer variantIndex,
      ErrorCode errorCode,
      String errorMessage,
      Map<String, Object> metadata) {
    this.value = value;
    this.reason = Objects.requireNonNull(reason, "reason");
    this.variantIndex = variantIndex;
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
    this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  public T value() {
    return value;
  }

  public Reason reason() {
    return reason;
  }

  public Integer variantIndex() {
    return variantIndex;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  public String errorMessage() {
    return errorMessage;
  }

  public Map<String, Object> metadata() {
    return metadata;
  }

  @Override
  public String toString() {
    return "EvaluationDetails{value="
        + value
        + ", reason="
        + reason
        + ", variantIndex="
        + variantIndex
        + ", errorCode="
        + errorCode
        + ", errorMessage="
        + errorMessage
        + ", metadata="
        + metadata
        + '}';
  }
}
