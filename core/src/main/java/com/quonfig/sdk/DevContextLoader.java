package com.quonfig.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.eval.ContextSet;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Loads the dev-only {@code quonfig-user} context written by {@code qfg login}, mirroring {@code
 * sdk-node/src/devContext.ts}.
 *
 * <p>{@code qfg login} writes {@code ~/.quonfig/tokens.json} for the production domain ({@code
 * quonfig.com}) and {@code ~/.quonfig/tokens-<domain-with-dashes>.json} for any other domain (e.g.
 * {@code tokens-quonfig-staging-com.json}). The SDK derives the domain from its first {@code
 * apiUrls} entry by stripping a leading {@code app.} or {@code primary.} subdomain; with no apiUrls
 * (datadir mode) the filename is plain {@code tokens.json}.
 *
 * <p>The file is parsed for a {@code userEmail} field and turned into a {@code { "quonfig-user": {
 * "email": <userEmail> } }} context. The loader is inert by construction in production: production
 * servers never run {@code qfg login}, so the tokens file is absent and rules keyed on {@code
 * quonfig-user.email} are dead code. A missing/unreadable file no-ops silently; an unparseable file
 * or a parse error logs a single warning and no-ops.
 */
final class DevContextLoader {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DevContextLoader() {}

  /**
   * Reads the per-domain tokens file and returns a {@link ContextSet} with the {@code quonfig-user}
   * named context populated from {@code userEmail}, or {@code null} when the file is missing,
   * unreadable, unparseable, or has no {@code userEmail}.
   */
  static ContextSet load(List<String> apiUrls, Logger logger) {
    Path path = homeDir().resolve(".quonfig").resolve(tokenFilenameForApiUrls(apiUrls));

    if (!Files.isRegularFile(path)) {
      return null;
    }

    String raw;
    try {
      raw = Files.readString(path);
    } catch (IOException e) {
      // Unreadable (permissions, vanished between stat and read) — stay silent, matching
      // sdk-node's "missing file no-ops without a warning".
      return null;
    }

    String email;
    try {
      JsonNode root = MAPPER.readTree(raw);
      JsonNode emailNode = root.path("userEmail");
      if (!emailNode.isTextual()) {
        return null;
      }
      email = emailNode.asText();
    } catch (IOException e) {
      if (logger != null) {
        logger.warn(
            "quonfig: dev-context could not parse {} ({}); skipping injection",
            path,
            e.getMessage());
      }
      return null;
    }

    if (email.isEmpty()) {
      return null;
    }

    return new ContextSet().withNamedContext("quonfig-user", Map.of("email", email));
  }

  /**
   * Picks the per-domain tokens filename, mirroring {@code qfg login}'s token storage: {@code
   * tokens.json} for the default {@code quonfig.com} domain, {@code
   * tokens-<domain-with-dashes>.json} otherwise.
   */
  static String tokenFilenameForApiUrls(List<String> apiUrls) {
    String domain = deriveDomainFromApiUrls(apiUrls);
    if (domain.isEmpty() || "quonfig.com".equals(domain)) {
      return "tokens.json";
    }
    return "tokens-" + domain.replace('.', '-') + ".json";
  }

  private static String deriveDomainFromApiUrls(List<String> apiUrls) {
    if (apiUrls == null || apiUrls.isEmpty() || apiUrls.get(0) == null) {
      return "";
    }
    String host;
    try {
      host = URI.create(apiUrls.get(0)).getHost();
    } catch (IllegalArgumentException e) {
      return "";
    }
    if (host == null || host.isEmpty()) {
      return "";
    }
    for (String prefix : new String[] {"app.", "primary."}) {
      if (host.startsWith(prefix)) {
        return host.substring(prefix.length());
      }
    }
    return host;
  }

  private static Path homeDir() {
    // Read user.home each call so tests can isolate the home dir via System.setProperty.
    return Path.of(System.getProperty("user.home", ""));
  }
}
