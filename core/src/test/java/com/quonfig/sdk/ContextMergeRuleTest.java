package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quonfig.sdk.eval.ContextSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the documented context-merge rule (qfg-2agi.24, REPLACE_NAMED): a newer tier's named context
 * replaces the WHOLE same-named context from an older tier, and named contexts the newer tier does
 * not mention survive. Each test uses disjoint attributes ({@code user.email} in the older tier,
 * {@code user.plan} in the newer one), so a property-level merge would fail it. Overriding the same
 * key would pass under both behaviours and pin nothing.
 *
 * <p>Tiers sdk-java exposes: global ({@link Options#globalContext()}), bound ({@link
 * Quonfig#withContext}), per-call (JIT), and the injected dev-context ({@code quonfig-user} from
 * {@code ~/.quonfig/tokens.json}, merged under the global context in {@code
 * Options.mergeDevContext}). {@link BoundQuonfig} has no {@code withContext}, so there are no
 * nested bound scopes to test.
 */
class ContextMergeRuleTest {

  private static final String PRESENT = "PRESENT";

  @TempDir Path workspaceDir;
  @TempDir Path tmpHome;
  private String savedUserHome;

  @BeforeEach
  void setUp() throws Exception {
    savedUserHome = System.getProperty("user.home");
    System.setProperty("user.home", tmpHome.toString());
    Files.createDirectories(tmpHome.resolve(".quonfig"));

    Files.writeString(
        workspaceDir.resolve("quonfig.json"),
        "{\"workspace\":\"test-ws\",\"environments\":[\"production\"]}");
    Files.createDirectories(workspaceDir.resolve("configs"));
    Files.createDirectories(workspaceDir.resolve("feature-flags"));
    Files.createDirectories(workspaceDir.resolve("segments"));
    writeProbe("p-email", "user.email", "a@x.com");
    writeProbe("p-plan", "user.plan", "pro");
    writeProbe("p-team", "team.key", "t1");
    writeProbe("p-org", "org.id", "o1");
    writeProbe("p-dev-email", "quonfig-user.email", "bob@foo.com");
    writeProbe("p-dev-plan", "quonfig-user.plan", "pro");
  }

  @AfterEach
  void tearDown() {
    if (savedUserHome != null) {
      System.setProperty("user.home", savedUserHome);
    } else {
      System.clearProperty("user.home");
    }
  }

  /** A string config that returns PRESENT when {@code prop == value}, else "absent". */
  private void writeProbe(String key, String prop, String value) throws Exception {
    Files.writeString(
        workspaceDir.resolve("configs").resolve(key + ".json"),
        "{\"id\":\""
            + key
            + "\",\"key\":\""
            + key
            + "\",\"type\":\"config\",\"valueType\":\"string\",\"default\":{\"rules\":["
            + "{\"criteria\":[{\"propertyName\":\""
            + prop
            + "\",\"operator\":\"PROP_IS_ONE_OF\","
            + "\"valueToMatch\":{\"type\":\"stringList\",\"value\":[\""
            + value
            + "\"]}}],\"value\":{\"type\":\"string\",\"value\":\"PRESENT\"}},"
            + "{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"absent\"}}]}}");
  }

  private Options.Builder baseOptions() {
    return Options.builder()
        .datadir(workspaceDir.toString())
        .environment("production")
        .disableTelemetry(true);
  }

  private Quonfig client(ContextSet global) {
    return new Quonfig(baseOptions().enableQuonfigUserContext(false).globalContext(global).build());
  }

  private static ContextSet userEmailAndTeam() {
    return new ContextSet()
        .withNamedContext("user", Map.of("email", "a@x.com"))
        .withNamedContext("team", Map.of("key", "t1"));
  }

  private static ContextSet userPlan() {
    return new ContextSet().withNamedContext("user", Map.of("plan", "pro"));
  }

  /** Observed state as "email,plan,team,org" with P = PRESENT and - = absent. */
  private static String observe(java.util.function.Function<String, String> get) {
    StringBuilder sb = new StringBuilder();
    for (String k : new String[] {"p-email", "p-plan", "p-team", "p-org"}) {
      if (sb.length() > 0) sb.append(',');
      sb.append(PRESENT.equals(get.apply(k)) ? "P" : "-");
    }
    return sb.toString();
  }

  @Test
  void globalPlusPerCall_perCallUserReplacesWholeGlobalUser_teamSurvives() {
    try (Quonfig q = client(userEmailAndTeam())) {
      assertEquals("P,-,P,-", observe(k -> q.getString(k, "fallback")), "global only");
      assertEquals(
          "-,P,P,-",
          observe(k -> q.getString(k, "fallback", userPlan())),
          "per-call user{plan} must drop global user.email; team survives");
      assertEquals(
          "P,-,P,-", observe(k -> q.getString(k, "fallback")), "per-call context must not leak");
    }
  }

  @Test
  void globalPlusBound_boundUserReplacesWholeGlobalUser_teamSurvives() {
    try (Quonfig q = client(userEmailAndTeam())) {
      BoundQuonfig bound = q.withContext(userPlan());
      assertEquals(
          "-,P,P,-",
          observe(k -> bound.getString(k, "fallback")),
          "bound user{plan} must drop global user.email; team survives");
    }
  }

  @Test
  void boundPlusPerCall_perCallUserReplacesWholeBoundUser_teamSurvives() {
    try (Quonfig q = client(null)) {
      BoundQuonfig bound = q.withContext(userEmailAndTeam());
      assertEquals("P,-,P,-", observe(k -> bound.getString(k, "fallback")), "bound only");
      assertEquals(
          "-,P,P,-",
          observe(k -> bound.getString(k, "fallback", userPlan())),
          "per-call user{plan} must drop bound user.email; team survives");
      assertEquals(
          "P,-,P,-",
          observe(k -> bound.getString(k, "fallback")),
          "per-call context must not leak into the bound scope");
    }
  }

  @Test
  void globalPlusBoundPlusPerCall_unmentionedContextsSurviveFromEveryTier() {
    try (Quonfig q = client(userEmailAndTeam())) {
      BoundQuonfig bound =
          q.withContext(new ContextSet().withNamedContext("org", Map.of("id", "o1")));
      assertEquals(
          "-,P,P,P",
          observe(k -> bound.getString(k, "fallback", userPlan())),
          "per-call user replaces global user; global team and bound org survive");
    }
  }

  @Test
  void devContext_customerQuonfigUserReplacesWholeInjectedQuonfigUser() throws Exception {
    Files.writeString(
        tmpHome.resolve(".quonfig").resolve("tokens.json"), "{\"userEmail\":\"bob@foo.com\"}");
    ContextSet customer = new ContextSet().withNamedContext("quonfig-user", Map.of("plan", "pro"));
    try (Quonfig q = new Quonfig(baseOptions().globalContext(customer).build())) {
      assertEquals(
          "absent",
          q.getString("p-dev-email", "fallback"),
          "customer quonfig-user{plan} must drop the injected quonfig-user.email");
      assertEquals(PRESENT, q.getString("p-dev-plan", "fallback"));
    }
  }

  @Test
  void devContext_survivesAnUnrelatedCustomerGlobalContext() throws Exception {
    Files.writeString(
        tmpHome.resolve(".quonfig").resolve("tokens.json"), "{\"userEmail\":\"bob@foo.com\"}");
    try (Quonfig q = new Quonfig(baseOptions().globalContext(userEmailAndTeam()).build())) {
      assertEquals(PRESENT, q.getString("p-dev-email", "fallback"));
      assertEquals("P,-,P,-", observe(k -> q.getString(k, "fallback")));
    }
  }
}
