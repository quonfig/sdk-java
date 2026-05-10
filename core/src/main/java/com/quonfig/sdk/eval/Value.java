package com.quonfig.sdk.eval;

import java.util.Objects;

/**
 * Universal value wrapper. The runtime Java type of {@link #value()} depends on {@link #type()}:
 *
 * <ul>
 *   <li>BOOL → {@code Boolean}
 *   <li>INT → {@code Long}
 *   <li>DOUBLE → {@code Double}
 *   <li>STRING / LOG_LEVEL / DURATION → {@code String}
 *   <li>JSON → native Object (Map / List / String / Number / Boolean / null)
 *   <li>STRING_LIST → {@code List<String>}
 *   <li>WEIGHTED_VALUES → {@code Map<String, Object>} or a typed weighted-values record
 *   <li>SCHEMA / PROVIDED → opaque Object
 * </ul>
 */
public final class Value {
  private final ValueType type;
  private final Object value;
  private final boolean confidential;
  private final String decryptWith;

  public Value(ValueType type, Object value) {
    this(type, value, false, null);
  }

  public Value(ValueType type, Object value, boolean confidential, String decryptWith) {
    this.type = Objects.requireNonNull(type, "type");
    this.value = value;
    this.confidential = confidential;
    this.decryptWith = decryptWith;
  }

  public ValueType type() {
    return type;
  }

  public Object value() {
    return value;
  }

  public boolean confidential() {
    return confidential;
  }

  public String decryptWith() {
    return decryptWith;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Value)) return false;
    Value that = (Value) o;
    return confidential == that.confidential
        && type == that.type
        && Objects.equals(value, that.value)
        && Objects.equals(decryptWith, that.decryptWith);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, value, confidential, decryptWith);
  }

  @Override
  public String toString() {
    return "Value{" + type + ", " + value + "}";
  }
}
