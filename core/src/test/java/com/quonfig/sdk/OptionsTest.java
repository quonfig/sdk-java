package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.eval.Resolver;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies Options handles env-var fallbacks and URL derivation per the qfg-mol-1q2 spec without
 * touching the real process environment (uses an injected {@link Resolver.EnvLookup}).
 */
class OptionsTest {

  private static Resolver.EnvLookup envFromMap(Map<String, String> entries) {
    return key -> Optional.ofNullable(entries.get(key));
  }

  @Test
  void domain_defaultsToQuonfigCom_andDerivesPrimarySecondaryStreamTelemetry() {
    Options o = Options.builder().envLookup(envFromMap(Map.of())).build();
    assertEquals("quonfig.com", o.domain());
    assertEquals(
        List.of("https://primary.quonfig.com", "https://secondary.quonfig.com"), o.apiUrls());
    assertEquals(
        List.of("https://stream.primary.quonfig.com", "https://stream.secondary.quonfig.com"),
        o.streamUrls());
    assertEquals("https://telemetry.quonfig.com", o.telemetryUrl());
  }

  @Test
  void domain_envVar_overridesDefault() {
    Options o =
        Options.builder().envLookup(envFromMap(Map.of("QUONFIG_DOMAIN", "example.com"))).build();
    assertEquals("example.com", o.domain());
    assertEquals(
        List.of("https://primary.example.com", "https://secondary.example.com"), o.apiUrls());
  }

  @Test
  void domain_explicitOption_winsOverEnvVar() {
    Map<String, String> env = new HashMap<>();
    env.put("QUONFIG_DOMAIN", "from-env.com");
    Options o = Options.builder().envLookup(envFromMap(env)).domain("from-option.com").build();
    assertEquals("from-option.com", o.domain());
  }

  @Test
  void apiUrls_explicit_winsAndOverridesUrlDerivation() {
    Options o =
        Options.builder()
            .envLookup(envFromMap(Map.of("QUONFIG_DOMAIN", "ignored.com")))
            .apiUrls(List.of("http://localhost:6550"))
            .build();
    assertEquals(List.of("http://localhost:6550"), o.apiUrls());
    // streamUrls still derives from the explicit override
    assertEquals(List.of("http://stream.localhost:6550"), o.streamUrls());
  }

  @Test
  void sdkKey_envVar_fallback() {
    Options o =
        Options.builder()
            .envLookup(envFromMap(Map.of("QUONFIG_BACKEND_SDK_KEY", "sk-abc")))
            .build();
    assertEquals("sk-abc", o.sdkKey());
  }

  @Test
  void environment_envVar_fallback() {
    Options o =
        Options.builder()
            .envLookup(envFromMap(Map.of("QUONFIG_ENVIRONMENT", "production")))
            .build();
    assertEquals("production", o.environment());
  }

  @Test
  void initTimeout_defaults_to10seconds() {
    Options o = Options.builder().envLookup(envFromMap(Map.of())).build();
    assertEquals(Duration.ofSeconds(10), o.initTimeout());
  }

  @Test
  void enablePolling_defaults_false() {
    Options o = Options.builder().envLookup(envFromMap(Map.of())).build();
    assertTrue(!o.enablePolling());
  }

  @Test
  void datadir_unset_byDefault() {
    Options o = Options.builder().envLookup(envFromMap(Map.of())).build();
    assertNull(o.datadir());
  }
}
