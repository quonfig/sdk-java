package com.quonfig.sdk.micronaut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.eval.ContextSet;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.http.simple.SimpleHttpRequest;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: {@link MicronautContextStore} stashes / reads / clears a {@link ContextSet} on the
 * current Micronaut HTTP request. Without an active request, every operation is a quiet no-op.
 *
 * <p>Micronaut 4 replaced {@code ServerRequestContext.set()} with scoped {@code with(request,
 * supplier)} blocks; tests use that to simulate the active request.
 */
class MicronautContextStoreTest {

  private final ContextSet userContext =
      new ContextSet().withNamedContext("user", Map.of("country", "us"));

  private static <T> T inRequest(Supplier<T> action) {
    HttpRequest<String> req = new SimpleHttpRequest<>(HttpMethod.POST, "http://localhost/", "body");
    return ServerRequestContext.with(req, action);
  }

  @Nested
  class WithoutActiveRequest {

    @Test
    void getReturnsEmpty() {
      assertFalse(MicronautContextStore.getContext().isPresent());
    }

    @Test
    void setIsNoOp() {
      Optional<ContextSet> prev = MicronautContextStore.setContext(userContext);
      assertFalse(prev.isPresent());
      assertFalse(MicronautContextStore.getContext().isPresent());
    }

    @Test
    void clearIsNoOp() {
      assertFalse(MicronautContextStore.clearContext().isPresent());
    }
  }

  @Nested
  class WithActiveRequest {

    @Test
    void getInitiallyEmpty() {
      Boolean present = inRequest(() -> MicronautContextStore.getContext().isPresent());
      assertFalse(present);
    }

    @Test
    void setThenGetRoundTrips() {
      Object got =
          inRequest(
              () -> {
                MicronautContextStore.setContext(userContext);
                return MicronautContextStore.getContext()
                    .map(cs -> cs.getContextValue("user.country").value())
                    .orElse(null);
              });
      assertEquals("us", got);
    }

    @Test
    void setReturnsPriorContext() {
      ContextSet replacement = new ContextSet().withNamedContext("user", Map.of("country", "uk"));
      Object[] result =
          inRequest(
              () -> {
                MicronautContextStore.setContext(userContext);
                Optional<ContextSet> prev = MicronautContextStore.setContext(replacement);
                Object current =
                    MicronautContextStore.getContext()
                        .map(cs -> cs.getContextValue("user.country").value())
                        .orElse(null);
                Object prevCountry =
                    prev.map(cs -> cs.getContextValue("user.country").value()).orElse(null);
                return new Object[] {prevCountry, current};
              });
      assertEquals("us", result[0]);
      assertEquals("uk", result[1]);
    }

    @Test
    void clearReturnsAndRemoves() {
      Boolean[] state =
          inRequest(
              () -> {
                MicronautContextStore.setContext(userContext);
                Optional<ContextSet> cleared = MicronautContextStore.clearContext();
                return new Boolean[] {
                  cleared.isPresent(), MicronautContextStore.getContext().isPresent()
                };
              });
      assertTrue(state[0]);
      assertFalse(state[1]);
    }
  }
}
