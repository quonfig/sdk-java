package com.quonfig.sdk.chaos;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny client for the toxiproxy admin API ({@code http://127.0.0.1:8474} by default). Just enough
 * to (re)create proxies, list/delete toxics, enable/disable proxies, and add new toxics. Mirrors
 * the {@code toxiproxyClient} type in sdk-go's chaos harness.
 */
final class ToxiproxyClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String base;
  private final HttpClient http;

  ToxiproxyClient(String base) {
    String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    this.base = b;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  void ping() throws IOException, InterruptedException {
    HttpResponse<String> r = get("/version");
    if (r.statusCode() != 200) {
      throw new IOException("toxiproxy /version: HTTP " + r.statusCode());
    }
  }

  /**
   * Recreate {@code name} pointing at the listen/upstream addresses. Idempotent — deletes any
   * existing proxy of the same name first.
   */
  void upsertProxy(String name, String listen, String upstream)
      throws IOException, InterruptedException {
    try {
      delete("/proxies/" + name);
    } catch (IOException ignored) {
      // 404 ok
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("listen", listen);
    body.put("upstream", upstream);
    body.put("enabled", true);
    HttpResponse<String> r = post("/proxies", MAPPER.writeValueAsString(body));
    if (r.statusCode() / 100 != 2) {
      throw new IOException("create proxy " + name + ": HTTP " + r.statusCode() + " — " + r.body());
    }
  }

  void clearToxics(String proxy) throws IOException, InterruptedException {
    HttpResponse<String> r = get("/proxies/" + proxy + "/toxics");
    if (r.statusCode() == 404) return;
    if (r.statusCode() / 100 != 2) {
      throw new IOException("list toxics " + proxy + ": HTTP " + r.statusCode());
    }
    List<Map<String, Object>> toxics =
        MAPPER.readValue(
            r.body(), MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
    for (Map<String, Object> t : toxics) {
      Object nm = t.get("name");
      if (!(nm instanceof String) || ((String) nm).isEmpty()) continue;
      try {
        delete("/proxies/" + proxy + "/toxics/" + nm);
      } catch (IOException ignored) {
        // best-effort
      }
    }
  }

  void setEnabled(String proxy, boolean enabled) throws IOException, InterruptedException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("enabled", enabled);
    HttpResponse<String> r = post("/proxies/" + proxy, MAPPER.writeValueAsString(body));
    if (r.statusCode() / 100 != 2) {
      throw new IOException(
          "set " + proxy + " enabled=" + enabled + ": HTTP " + r.statusCode() + " — " + r.body());
    }
  }

  /** Posts a toxic. {@code stream} defaults to "downstream" when null/empty. */
  void addToxic(
      String proxy, String name, String type, String stream, Map<String, Object> attributes)
      throws IOException, InterruptedException {
    String s = (stream == null || stream.isEmpty()) ? "downstream" : stream;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("type", type);
    body.put("stream", s);
    body.put("attributes", attributes);
    HttpResponse<String> r = post("/proxies/" + proxy + "/toxics", MAPPER.writeValueAsString(body));
    if (r.statusCode() / 100 != 2) {
      throw new IOException(
          "add toxic " + proxy + "/" + name + ": HTTP " + r.statusCode() + " — " + r.body());
    }
  }

  void removeToxic(String proxy, String name) throws IOException, InterruptedException {
    HttpResponse<String> r = delete("/proxies/" + proxy + "/toxics/" + name);
    if (r.statusCode() != 204 && r.statusCode() != 404) {
      throw new IOException("delete toxic " + proxy + "/" + name + ": HTTP " + r.statusCode());
    }
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(base + path)).GET().build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body)
      throws IOException, InterruptedException {
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> delete(String path) throws IOException, InterruptedException {
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(base + path)).DELETE().build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }
}
