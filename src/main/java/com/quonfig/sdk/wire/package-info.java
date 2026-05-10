/**
 * Jackson-bound POJOs that model the over-the-wire payloads served by {@code api-delivery}. Kept
 * minimal — internal evaluation types live in {@link com.quonfig.sdk.eval} and are populated by
 * {@link com.quonfig.sdk.DatadirLoader}; the wire types here are only used by the HTTP transport
 * (qfg-oi0j.4) and any future tools that need to round-trip envelopes through Jackson.
 */
package com.quonfig.sdk.wire;
