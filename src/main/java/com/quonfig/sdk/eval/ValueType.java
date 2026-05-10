package com.quonfig.sdk.eval;

/** Type discriminator for {@link Value}. */
public enum ValueType {
  BOOL,
  INT,
  DOUBLE,
  STRING,
  JSON,
  STRING_LIST,
  LOG_LEVEL,
  WEIGHTED_VALUES,
  SCHEMA,
  PROVIDED,
  DURATION;
}
