package com.quonfig.sdk.telemetry;

/**
 * One evaluation observation pushed into the telemetry pipeline.
 *
 * <p>{@code reportableValue} is non-null when the underlying value is confidential or AES-GCM
 * encrypted; in that case the wire payload sends the redacted form instead of the plaintext.
 */
public final class EvaluationStat {
  private final String configId;
  private final String configKey;
  private final String configType;
  private final int ruleIndex;
  private final int weightedValueIndex;
  private final Object selectedValue;
  private final String reportableValue;
  private final int reason;

  public EvaluationStat(
      String configId,
      String configKey,
      String configType,
      int ruleIndex,
      int weightedValueIndex,
      Object selectedValue,
      String reportableValue,
      int reason) {
    this.configId = configId;
    this.configKey = configKey;
    this.configType = configType;
    this.ruleIndex = ruleIndex;
    this.weightedValueIndex = weightedValueIndex;
    this.selectedValue = selectedValue;
    this.reportableValue = reportableValue;
    this.reason = reason;
  }

  public String configId() {
    return configId;
  }

  public String configKey() {
    return configKey;
  }

  public String configType() {
    return configType;
  }

  public int ruleIndex() {
    return ruleIndex;
  }

  public int weightedValueIndex() {
    return weightedValueIndex;
  }

  public Object selectedValue() {
    return selectedValue;
  }

  public String reportableValue() {
    return reportableValue;
  }

  public int reason() {
    return reason;
  }
}
