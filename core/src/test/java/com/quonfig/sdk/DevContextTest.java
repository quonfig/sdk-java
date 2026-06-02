package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.quonfig.sdk.eval.ContextSet;
import com.quonfig.sdk.eval.Resolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage for the {@code quonfig-user.email} dev-context loader and the default-on knob.
 * Mirrors {@code sdk-node/test/dev-context.test.ts}.
 *
 * <p>The dev-override flag ({@code feature-flag.dev-override}) lives in the shared
 * integration-test- data datadir: env {@code Production}, rule {@code quonfig-user.email
 * PROP_IS_ONE_OF ["bob@foo.com"] -> true}, else {@code false}. A client whose global context
 * carries that email therefore resolves the flag to {@code true}; otherwise {@code false}.
 *
 * <p>{@code user.home} is redirected to a temp dir per test so the loader reads only the
 * tokens.json we write (and never the developer's real {@code ~/.quonfig}).
 */
class DevContextTest {

  private static final String FLAG = "feature-flag.dev-override";
  private static final String ENV = "Production";

  // QUONFIG_DEV_CONTEXT must be controllable without mutating the real process environment, so
  // route the SDK's env lookups through this per-test map.
  private final Map<String, String> env = new HashMap<>();
  private final Resolver.EnvLookup envLookup = key -> Optional.ofNullable(env.get(key));

  private String savedUserHome;
  private String datadir;

  @TempDir Path tmpHome;

  @BeforeEach
  void setUp() throws IOException {
    savedUserHome = System.getProperty("user.home");
    System.setProperty("user.home", tmpHome.toString());
    Files.createDirectories(tmpHome.resolve(".quonfig"));
    datadir = locateDatadir();
  }

  @AfterEach
  void tearDown() {
    if (savedUserHome != null) {
      System.setProperty("user.home", savedUserHome);
    } else {
      System.clearProperty("user.home");
    }
  }

  private void writeTokens(String json) throws IOException {
    Files.writeString(tmpHome.resolve(".quonfig").resolve("tokens.json"), json);
  }

  private Options.Builder baseOptions() {
    return Options.builder()
        .datadir(datadir)
        .environment(ENV)
        .disableTelemetry(true)
        .envLookup(envLookup);
  }

  // ---- 1. RED->GREEN headline: default-on with tokens.json injects the email ----

  @Test
  @DisplayName("DEFAULT client (no opt-in) + tokens.json with bob@foo.com fires the dev-override")
  void defaultOnInjectsFromTokensFile() throws IOException {
    writeTokens("{\"userEmail\":\"bob@foo.com\",\"accessToken\":\"x\",\"domain\":\"quonfig.com\"}");

    Quonfig q = new Quonfig(baseOptions().build());
    assertEquals(Boolean.TRUE, q.getBool(FLAG, false));
    q.close();
  }

  @Test
  @DisplayName("default-on populates quonfig-user.email in the resolved global context")
  void defaultOnPopulatesGlobalContext() throws IOException {
    writeTokens("{\"userEmail\":\"bob@foo.com\"}");

    Options opts = baseOptions().build();
    ContextSet g = opts.globalContext();
    assertEquals("bob@foo.com", g.getContextValue("quonfig-user.email").value());
  }

  // ---- 2. explicit option false disables ----

  @Test
  @DisplayName("enableQuonfigUserContext=false suppresses injection despite tokens.json")
  void explicitFalseDisables() throws IOException {
    writeTokens("{\"userEmail\":\"bob@foo.com\"}");

    Quonfig q = new Quonfig(baseOptions().enableQuonfigUserContext(false).build());
    assertEquals(Boolean.FALSE, q.getBool(FLAG, false));
    q.close();
  }

  // ---- 3. QUONFIG_DEV_CONTEXT=false disables ----

  @Test
  @DisplayName("QUONFIG_DEV_CONTEXT=false suppresses injection despite tokens.json")
  void envFalseDisables() throws IOException {
    writeTokens("{\"userEmail\":\"bob@foo.com\"}");
    env.put("QUONFIG_DEV_CONTEXT", "false");

    Quonfig q = new Quonfig(baseOptions().build());
    assertEquals(Boolean.FALSE, q.getBool(FLAG, false));
    q.close();
  }

  // ---- 4. explicit option true overrides QUONFIG_DEV_CONTEXT=false ----

  @Test
  @DisplayName("enableQuonfigUserContext=true overrides QUONFIG_DEV_CONTEXT=false")
  void explicitTrueBeatsEnvFalse() throws IOException {
    writeTokens("{\"userEmail\":\"bob@foo.com\"}");
    env.put("QUONFIG_DEV_CONTEXT", "false");

    Quonfig q = new Quonfig(baseOptions().enableQuonfigUserContext(true).build());
    assertEquals(Boolean.TRUE, q.getBool(FLAG, false));
    q.close();
  }

  // ---- 5. no tokens file -> inert (no error) ----

  @Test
  @DisplayName("no tokens file is inert: flag is false, no global context injected")
  void noTokensFileIsInert() {
    Options opts = baseOptions().build();
    assertNull(opts.globalContext());

    Quonfig q = new Quonfig(opts);
    assertEquals(Boolean.FALSE, q.getBool(FLAG, false));
    q.close();
  }

  @Test
  @DisplayName("unparseable tokens file is inert (no throw)")
  void unparseableTokensFileIsInert() throws IOException {
    writeTokens("{not valid json");

    Quonfig q = new Quonfig(baseOptions().build());
    assertEquals(Boolean.FALSE, q.getBool(FLAG, false));
    q.close();
  }

  // ---- 6. customer-supplied quonfig-user wins on collision ----

  @Test
  @DisplayName("customer-supplied quonfig-user context wins over the injected dev-context")
  void customerContextWinsOnCollision() throws IOException {
    writeTokens("{\"userEmail\":\"bob@foo.com\"}");

    ContextSet customer =
        new ContextSet().withNamedContext("quonfig-user", Map.of("email", "nobody@x.com"));

    Options opts = baseOptions().globalContext(customer).build();
    assertEquals(
        "nobody@x.com", opts.globalContext().getContextValue("quonfig-user.email").value());

    Quonfig q = new Quonfig(opts);
    // The customer's non-matching email means the override rule does NOT fire.
    assertEquals(Boolean.FALSE, q.getBool(FLAG, false));
    q.close();
  }

  /** Locate the integration-test-data datadir the same way {@code integration.TestSetup} does. */
  private static String locateDatadir() {
    String userDir = System.getProperty("user.dir");
    Path candidate =
        Paths.get(userDir, "..", "integration-test-data", "data", "integration-tests").normalize();
    if (Files.isDirectory(candidate)) return candidate.toString();
    Path alt = Paths.get(userDir, "integration-test-data", "data", "integration-tests").normalize();
    return alt.toString();
  }
}
