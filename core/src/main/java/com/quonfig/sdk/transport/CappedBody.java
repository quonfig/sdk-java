package com.quonfig.sdk.transport;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Body handling for the size-caps rule (qfg-6vno.20.2). Replaces {@code BodyHandlers.ofString()} on
 * every {@link HttpTransport} request so no response body is buffered without a bound:
 *
 * <ul>
 *   <li>2xx: a {@code Content-Length} over {@link HttpTransport#MAX_CONFIG_BODY_BYTES} fails before
 *       any byte is read; otherwise bytes are counted as they arrive and the read stops (the
 *       subscription is cancelled, closing the connection) at cap + 1. Either way the body future
 *       fails with {@link BodyTooLargeException}.
 *   <li>Any other status (non-2xx, 304): at most {@link HttpTransport#MAX_BODY_EXCERPT} bytes are
 *       read, then the subscription is cancelled. The excerpt is the body the caller sees.
 * </ul>
 */
final class CappedBody {

  private CappedBody() {}

  static final HttpResponse.BodyHandler<String> HANDLER =
      info -> {
        int sc = info.statusCode();
        if (sc >= 200 && sc < 300) {
          long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1L);
          if (declared > HttpTransport.MAX_CONFIG_BODY_BYTES) {
            return new Subscriber(sc, HttpTransport.MAX_CONFIG_BODY_BYTES, false, true);
          }
          return new Subscriber(sc, HttpTransport.MAX_CONFIG_BODY_BYTES, false, false);
        }
        return new Subscriber(sc, HttpTransport.MAX_BODY_EXCERPT, true, false);
      };

  /**
   * Decodes at most {@code maxBytes} of UTF-8 {@code bytes}, dropping a character the cut splits so
   * the excerpt never ends in a replacement character.
   */
  static String decodePrefix(byte[] bytes, int maxBytes) {
    int n = Math.min(bytes.length, maxBytes);
    CharsetDecoder dec =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    CharBuffer out = CharBuffer.allocate(n);
    dec.decode(ByteBuffer.wrap(bytes, 0, n), out, false);
    out.flip();
    return out.toString();
  }

  private static final class Subscriber implements HttpResponse.BodySubscriber<String> {
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final int statusCode;
    private final int limit;
    private final boolean truncate;
    private final boolean failNow;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    Subscriber(int statusCode, int limit, boolean truncate, boolean failNow) {
      this.statusCode = statusCode;
      this.limit = limit;
      this.truncate = truncate;
      this.failNow = failNow;
    }

    @Override
    public CompletionStage<String> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      if (failNow) {
        s.cancel();
        result.completeExceptionally(new BodyTooLargeException(statusCode, limit));
        return;
      }
      s.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
      if (result.isDone()) return;
      for (ByteBuffer b : items) {
        int room = limit - buf.size();
        int n = b.remaining();
        if (n > room) {
          if (truncate) {
            byte[] chunk = new byte[room];
            b.get(chunk);
            buf.write(chunk, 0, room);
            subscription.cancel();
            result.complete(decode());
          } else {
            subscription.cancel();
            result.completeExceptionally(new BodyTooLargeException(statusCode, limit));
          }
          return;
        }
        byte[] chunk = new byte[n];
        b.get(chunk);
        buf.write(chunk, 0, n);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable t) {
      result.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
      result.complete(decode());
    }

    private String decode() {
      byte[] bytes = buf.toByteArray();
      if (!truncate) return new String(bytes, StandardCharsets.UTF_8);
      return decodePrefix(bytes, bytes.length);
    }
  }
}
