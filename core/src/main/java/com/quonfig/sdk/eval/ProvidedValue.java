package com.quonfig.sdk.eval;

import java.util.Objects;

/**
 * Wire-format payload for {@link ValueType#PROVIDED} values.
 *
 * <p>{@code source} is the lookup mechanism (currently only {@code "ENV_VAR"}). {@code lookup} is
 * the key to read from that source — for {@code ENV_VAR}, the environment variable name.
 */
public final class ProvidedValue {
  private final String source;
  private final String lookup;

  public ProvidedValue(String source, String lookup) {
    this.source = Objects.requireNonNull(source, "source");
    this.lookup = Objects.requireNonNull(lookup, "lookup");
  }

  public String source() {
    return source;
  }

  public String lookup() {
    return lookup;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ProvidedValue)) return false;
    ProvidedValue that = (ProvidedValue) o;
    return source.equals(that.source) && lookup.equals(that.lookup);
  }

  @Override
  public int hashCode() {
    return Objects.hash(source, lookup);
  }

  @Override
  public String toString() {
    return "ProvidedValue{" + source + ":" + lookup + "}";
  }
}
