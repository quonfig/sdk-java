package com.quonfig.sdk.eval;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The read side of the qf1 secret format: parses a stored {@code
 * qf1:<KEYSET>:<FINGERPRINT>:<PAYLOAD>} value, finds the private key by (keyset name, fingerprint)
 * in the environment variable named by the keyset, and decrypts it with X25519, HKDF-SHA256 and
 * AES-256-GCM.
 *
 * <p>Spec: {@code project/plans/secrets-qf1-spec.md}; section numbers in comments refer to it.
 * There is no writer: SDKs only read. Every regular expression is a full-string match ({@link
 * java.util.regex.Matcher#matches()}), every split keeps trailing empty fields ({@code split(":",
 * -1)}), and the env value is stripped of space, tab, CR and LF only (6.2), never {@link
 * String#trim()} or {@link String#strip()}.
 *
 * <p>Errors are {@link Qf1Exception}s whose text is {@code qf1 <class>: <detail>} (9.2). The text
 * never contains key material, any part of a keyset env value, the payload or the plaintext, and
 * the exception carries no cause and no suppressed exceptions.
 *
 * <p>Thread-safe. A parsed keyset is cached per env var name and reused only while the variable's
 * value is unchanged (the cache compares a SHA-256 of the value, never the value itself). An unset
 * variable is never cached.
 */
final class Qf1Reader {

  /** Normative reader error classes (spec 9.1). */
  enum ErrorClass {
    MALFORMED("malformed"),
    UNSUPPORTED_VERSION("unsupported_version"),
    INVALID_KEYSET_NAME("invalid_keyset_name"),
    KEYSET_MISSING("keyset_missing"),
    KEYSET_INVALID("keyset_invalid"),
    KEY_NOT_HELD("key_not_held"),
    INVALID_EPHEMERAL_KEY("invalid_ephemeral_key"),
    DECRYPT_FAILED("decrypt_failed"),
    INVALID_KEY_ENTRY("invalid_key_entry");

    private final String wireName;

    ErrorClass(String wireName) {
      this.wireName = wireName;
    }

    /** The class name as the spec writes it, e.g. {@code key_not_held}. */
    String wireName() {
      return wireName;
    }
  }

  /** A qf1 failure. No cause, no suppressed exceptions: the message is all there is. */
  static final class Qf1Exception extends Exception {
    private static final long serialVersionUID = 1L;
    private final ErrorClass errorClass;
    private final String detail;

    Qf1Exception(ErrorClass errorClass, String detail) {
      super("qf1 " + errorClass.wireName() + ": " + detail, null, false, true);
      this.errorClass = errorClass;
      this.detail = detail;
    }

    ErrorClass errorClass() {
      return errorClass;
    }

    String detail() {
      return detail;
    }
  }

  static final String KEYSET_NAME_PATTERN = "^QUONFIG_KEYSET(_[A-Z0-9]+)*$";
  static final byte[] HKDF_INFO = "quonfig.com/qf1/X25519".getBytes(StandardCharsets.US_ASCII);

  private static final String VERSION_PREFIX = "qf1:";
  private static final String PRIVATE_KEY_PREFIX = "qf1-priv-";
  private static final int KEY_LENGTH = 32;
  private static final int TAG_BITS = 128;
  private static final int MIN_PAYLOAD_LENGTH = 48;
  private static final int OKM_LENGTH = 44;
  private static final int AES_KEY_LENGTH = 32;
  private static final int NAME_QUOTE_LIMIT = 64;

  private static final Pattern KEYSET_NAME = Pattern.compile("QUONFIG_KEYSET(?:_[A-Z0-9]+)*");
  private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{8}");
  private static final Pattern OTHER_VERSION = Pattern.compile("qf[1-9][0-9]*:");
  private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

  /** X25519 base point u = 9, little-endian. */
  private static final byte[] BASE_POINT = basePoint();

  private final Resolver.EnvLookup envLookup;
  private final Map<String, CachedKeyset> cache = new ConcurrentHashMap<>();

  /** Most keyset names cached per reader; names are writer-chosen, so the map is bounded. */
  static final int MAX_CACHED_KEYSETS = 64;

  /** X25519 operations performed, process-wide (tests only: proves the keyset cache hits). */
  static final LongAdder KEY_AGREEMENTS = new LongAdder();

  Qf1Reader(Resolver.EnvLookup envLookup) {
    if (envLookup == null) throw new IllegalArgumentException("envLookup is required");
    this.envLookup = envLookup;
  }

  // ---------------------------------------------------------------------------------------------
  // Decrypt (spec 8)
  // ---------------------------------------------------------------------------------------------

  /**
   * Decrypts one stored qf1 value. Only the env var named by the value's keyset field is read, and
   * only after that name passes spec 2.
   */
  String decrypt(String value) throws Qf1Exception {
    Ciphertext ct = parse(value);
    try {
      return decryptParsed(ct);
    } catch (AlgorithmUnavailable u) {
      // The runtime's JCA provider refuses an algorithm (for example a FIPS-restricted provider
      // without X25519). Nothing is wrong with the value or the keyset; say so, per value.
      throw new Qf1Exception(
          ErrorClass.DECRYPT_FAILED,
          "this Java runtime does not provide "
              + u.algorithm
              + " (a restricted JCA provider, for example FIPS mode), so it cannot read qf1"
              + " secrets; the keyset "
              + shown(ct.keyset)
              + " and its entries were not checked");
    }
  }

  private String decryptParsed(Ciphertext ct) throws Qf1Exception {
    Keyset ks;
    try {
      ks = keyset(ct.keyset);
    } catch (Qf1Exception e) {
      if (e.errorClass() == ErrorClass.KEYSET_MISSING) {
        throw new Qf1Exception(
            ErrorClass.KEYSET_MISSING,
            "value is encrypted to "
                + shown(ct.keyset)
                + " fingerprint "
                + ct.fingerprint
                + "; "
                + e.detail());
      }
      throw e;
    }
    return ks.open(ct);
  }

  Keyset keyset(String name) throws Qf1Exception {
    Optional<String> found = envLookup.lookup(name);
    if (found == null || found.isEmpty()) {
      throw new Qf1Exception(ErrorClass.KEYSET_MISSING, "this runtime has no " + shown(name));
    }
    String value = found.get();
    byte[] digest = sha256(value.getBytes(StandardCharsets.UTF_8));
    CachedKeyset cached = cache.get(name);
    if (cached != null && MessageDigest.isEqual(cached.valueDigest, digest)) {
      return cached.get();
    }
    CachedKeyset fresh;
    try {
      fresh = new CachedKeyset(digest, parseKeyset(name, value), null, null);
    } catch (Qf1Exception e) {
      fresh = new CachedKeyset(digest, null, e.errorClass(), e.detail());
    }
    if (cache.containsKey(name) || cache.size() < MAX_CACHED_KEYSETS) cache.put(name, fresh);
    return fresh.get();
  }

  private static final class CachedKeyset {
    final byte[] valueDigest;
    final Keyset keyset;
    final ErrorClass errorClass;
    final String errorDetail;

    CachedKeyset(byte[] valueDigest, Keyset keyset, ErrorClass errorClass, String errorDetail) {
      this.valueDigest = valueDigest;
      this.keyset = keyset;
      this.errorClass = errorClass;
      this.errorDetail = errorDetail;
    }

    /** A fresh exception per call, so no thread rethrows another thread's instance. */
    Keyset get() throws Qf1Exception {
      if (keyset == null) throw new Qf1Exception(errorClass, errorDetail);
      return keyset;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Parse (spec 3.5)
  // ---------------------------------------------------------------------------------------------

  /** A parsed qf1 value. */
  static final class Ciphertext {
    final String keyset;
    final String fingerprint;
    final byte[] aad;
    final byte[] ephemeral;
    final byte[] sealed;

    Ciphertext(String keyset, String fingerprint, byte[] ephemeral, byte[] sealed) {
      this.keyset = keyset;
      this.fingerprint = fingerprint;
      this.aad = ("qf1:" + keyset + ":" + fingerprint + ":").getBytes(StandardCharsets.US_ASCII);
      this.ephemeral = ephemeral;
      this.sealed = sealed;
    }
  }

  /** Applies spec 3.5 in order; the first failing step decides the class. Reads no env. */
  static Ciphertext parse(String v) throws Qf1Exception {
    if (v == null || !v.startsWith(VERSION_PREFIX)) {
      if (v != null && OTHER_VERSION.matcher(v).lookingAt()) {
        throw new Qf1Exception(
            ErrorClass.UNSUPPORTED_VERSION,
            "value uses an unsupported qf version; this SDK reads qf1 only");
      }
      throw new Qf1Exception(ErrorClass.MALFORMED, "value is not a qf1 string");
    }
    String[] fields = v.split(":", -1);
    if (fields.length != 4) {
      throw new Qf1Exception(
          ErrorClass.MALFORMED, "value has " + fields.length + " colon-separated fields, want 4");
    }
    String keyset = fields[1];
    String fp = fields[2];
    if (!KEYSET_NAME.matcher(keyset).matches()) {
      // Field 2 is writer-controlled and failed the name rule, so it may hold anything (a pasted
      // key or plaintext): report only its length, never its text.
      throw new Qf1Exception(
          ErrorClass.INVALID_KEYSET_NAME,
          "keyset field ("
              + keyset.length()
              + " characters) does not match "
              + KEYSET_NAME_PATTERN);
    }
    if (!FINGERPRINT.matcher(fp).matches()) {
      throw new Qf1Exception(
          ErrorClass.MALFORMED, "fingerprint field is not 8 lowercase hex characters");
    }
    byte[] payload = decodeBase64url(fields[3]);
    if (payload == null) {
      throw new Qf1Exception(ErrorClass.MALFORMED, "payload is not canonical unpadded base64url");
    }
    if (payload.length < MIN_PAYLOAD_LENGTH) {
      throw new Qf1Exception(
          ErrorClass.MALFORMED,
          "payload is " + payload.length + " bytes, want at least " + MIN_PAYLOAD_LENGTH);
    }
    if ((payload[KEY_LENGTH - 1] & 0x80) != 0) {
      throw new Qf1Exception(ErrorClass.MALFORMED, "ephemeral key has its top bit set");
    }
    return new Ciphertext(
        keyset,
        fp,
        Arrays.copyOfRange(payload, 0, KEY_LENGTH),
        Arrays.copyOfRange(payload, KEY_LENGTH, payload.length));
  }

  int cachedKeysetCount() {
    return cache.size();
  }

  /** Spec 2, full-string match. */
  static boolean isValidKeysetName(String name) {
    return name != null && KEYSET_NAME.matcher(name).matches();
  }

  /**
   * A VALID keyset name as echoed in an error: at most 64 characters (9.2). Only names that passed
   * spec 2 reach this, so they hold only {@code A-Z 0-9 _}.
   */
  static String shown(String name) {
    return name.length() > NAME_QUOTE_LIMIT ? name.substring(0, NAME_QUOTE_LIMIT) + "..." : name;
  }

  /**
   * The strict canonical unpadded base64url decoder of spec 3.4: alphabet only (so no {@code =},
   * whitespace or {@code +/}), {@code length mod 4 != 1}, and decode-then-re-encode equality (which
   * rejects non-zero unused trailing bits). {@code null} means invalid. The JDK's URL decoder alone
   * accepts padding, so the checks come first.
   */
  static byte[] decodeBase64url(String s) {
    if (s.isEmpty() || s.length() % 4 == 1 || !BASE64URL.matcher(s).matches()) return null;
    byte[] b;
    try {
      b = Base64.getUrlDecoder().decode(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(b).equals(s)) {
      Arrays.fill(b, (byte) 0);
      return null;
    }
    return b;
  }

  // ---------------------------------------------------------------------------------------------
  // Key entries and the keyset env var (spec 6)
  // ---------------------------------------------------------------------------------------------

  /**
   * Parses {@code qf1-priv-} + 43 canonical base64url characters into the raw (unclamped) 32-byte
   * X25519 scalar (6.1). The caller should zero the result when done.
   */
  static byte[] parsePrivateKeyEntry(String s) throws Qf1Exception {
    if (!s.startsWith(PRIVATE_KEY_PREFIX) || s.length() != PRIVATE_KEY_PREFIX.length() + 43) {
      throw new Qf1Exception(ErrorClass.INVALID_KEY_ENTRY, "not a qf1 private key entry");
    }
    byte[] b = decodeBase64url(s.substring(PRIVATE_KEY_PREFIX.length()));
    if (b == null || b.length != KEY_LENGTH) {
      if (b != null) Arrays.fill(b, (byte) 0);
      throw new Qf1Exception(ErrorClass.INVALID_KEY_ENTRY, "not a qf1 private key entry");
    }
    return b;
  }

  /** Strips leading and trailing space, tab, CR and LF only (6.2 step 2). */
  static String stripEnvWhitespace(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && isEnvWhitespace(s.charAt(start))) start++;
    while (end > start && isEnvWhitespace(s.charAt(end - 1))) end--;
    return s.substring(start, end);
  }

  private static boolean isEnvWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
  }

  /** One held key: the private key and its derived 32-byte public key. */
  static final class HeldKey {
    final PrivateKey privateKey;
    final byte[] publicKey;

    HeldKey(PrivateKey privateKey, byte[] publicKey) {
      this.privateKey = privateKey;
      this.publicKey = publicKey;
    }
  }

  /** A parsed keyset env var: fingerprint to key (6.2). Immutable. */
  static final class Keyset {
    final String name;
    final Map<String, HeldKey> keys;

    Keyset(String name, Map<String, HeldKey> keys) {
      this.name = name;
      this.keys = keys;
    }

    /** Held fingerprints, sorted. Fingerprints are public. */
    List<String> fingerprints() {
      return new ArrayList<>(new TreeSet<>(keys.keySet()));
    }

    /** Spec 8 steps 3 to 7. Looks the key up by fingerprint; never tries keys one by one. */
    String open(Ciphertext ct) throws Qf1Exception {
      HeldKey held = keys.get(ct.fingerprint);
      if (held == null) {
        throw new Qf1Exception(
            ErrorClass.KEY_NOT_HELD,
            "value is encrypted to "
                + shown(ct.keyset)
                + " fingerprint "
                + ct.fingerprint
                + "; this runtime holds "
                + shown(name)
                + " fingerprints ["
                + String.join(" ", fingerprints())
                + "]");
      }
      byte[] shared = x25519(held.privateKey, ct.ephemeral);
      if (shared == null) {
        throw new Qf1Exception(
            ErrorClass.INVALID_EPHEMERAL_KEY,
            "X25519 shared secret for "
                + shown(ct.keyset)
                + " fingerprint "
                + ct.fingerprint
                + " is all zero");
      }
      byte[] okm = deriveOkm(shared, ct.ephemeral, held.publicKey);
      Arrays.fill(shared, (byte) 0);
      byte[] plain;
      try {
        plain = gcmOpen(okm, ct.aad, ct.sealed);
      } catch (AEADBadTagException e) {
        throw new Qf1Exception(
            ErrorClass.DECRYPT_FAILED,
            "authentication failed for " + shown(ct.keyset) + " fingerprint " + ct.fingerprint);
      } finally {
        Arrays.fill(okm, (byte) 0);
      }
      try {
        return decodeUtf8Strict(plain);
      } catch (CharacterCodingException e) {
        throw new Qf1Exception(
            ErrorClass.DECRYPT_FAILED,
            "plaintext for "
                + shown(ct.keyset)
                + " fingerprint "
                + ct.fingerprint
                + " is not UTF-8");
      } finally {
        Arrays.fill(plain, (byte) 0);
      }
    }
  }

  /** Parses the value of the env var named {@code name} (6.2 steps 2 to 5). */
  static Keyset parseKeyset(String name, String value) throws Qf1Exception {
    List<String> entries = new ArrayList<>();
    for (String piece : value.split(",", -1)) {
      String p = stripEnvWhitespace(piece);
      if (!p.isEmpty()) entries.add(p);
    }
    if (entries.isEmpty()) {
      throw new Qf1Exception(ErrorClass.KEYSET_MISSING, shown(name) + " is set but holds no keys");
    }
    Map<String, HeldKey> keys = new LinkedHashMap<>();
    for (int i = 0; i < entries.size(); i++) {
      byte[] raw;
      try {
        raw = parsePrivateKeyEntry(entries.get(i));
      } catch (Qf1Exception e) {
        throw new Qf1Exception(
            ErrorClass.KEYSET_INVALID,
            shown(name) + " entry " + (i + 1) + " is not a qf1 private key");
      }
      PrivateKey priv;
      byte[] pub;
      try {
        priv = privateKey(raw);
        pub = x25519(priv, BASE_POINT);
      } finally {
        Arrays.fill(raw, (byte) 0);
      }
      if (pub == null) {
        // X25519(k, 9) is never zero for a clamped k; reaching this is a platform bug.
        throw new IllegalStateException("qf1: X25519 base-point multiplication returned zero");
      }
      String fp = fingerprint(pub);
      HeldKey existing = keys.get(fp);
      if (existing != null) {
        if (MessageDigest.isEqual(existing.publicKey, pub)) continue; // same key, collapse (4)
        throw new Qf1Exception(
            ErrorClass.KEYSET_INVALID,
            shown(name) + " holds two different keys with fingerprint " + fp);
      }
      keys.put(fp, new HeldKey(priv, pub));
    }
    return new Keyset(name, Map.copyOf(keys));
  }

  /** {@code lowercase-hex(SHA-256(pub)[0:4])} (spec 4). */
  static String fingerprint(byte[] publicKey) {
    return HexFormat.of().formatHex(sha256(publicKey), 0, 4);
  }

  // ---------------------------------------------------------------------------------------------
  // Primitives (spec 5)
  // ---------------------------------------------------------------------------------------------

  /** A JCA algorithm the runtime refuses (no provider for it). Never cached; per value. */
  static final class AlgorithmUnavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final String algorithm;

    AlgorithmUnavailable(String algorithm) {
      super("qf1: this Java runtime does not provide " + algorithm, null, false, false);
      this.algorithm = algorithm;
    }
  }

  /** The X25519 KeyAgreement algorithm name. A test seam only, to simulate a FIPS provider. */
  static volatile String keyAgreementAlgorithm = "X25519";

  /** A JDK X25519 private key from the raw 32-byte scalar; the JDK clamps it at use (5.2). */
  static PrivateKey privateKey(byte[] raw) {
    try {
      return KeyFactory.getInstance("XDH")
          .generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, raw));
    } catch (NoSuchAlgorithmException e) {
      throw new AlgorithmUnavailable("XDH key factory (X25519)");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(
          "qf1: X25519 is unavailable (" + e.getClass().getSimpleName() + ")");
    }
  }

  /**
   * {@code X25519(priv, u)} over the 32 little-endian bytes of {@code u}, or {@code null} when the
   * result is all zero. The JDK refuses a small-order {@code u} at {@code doPhase} with {@code
   * InvalidKeyException: Point has small order}; that refusal IS the all-zero failure of 5.2. The
   * JDK reduces a non-canonical u mod p (RFC 7748); it does not mask the top bit, which {@link
   * #parse} has already rejected (3.5 step 6), so building u from all 32 bytes cannot diverge.
   */
  static byte[] x25519(PrivateKey priv, byte[] uLittleEndian) {
    try {
      byte[] be = new byte[KEY_LENGTH];
      for (int i = 0; i < KEY_LENGTH; i++) be[i] = uLittleEndian[KEY_LENGTH - 1 - i];
      KeyFactory xdh;
      try {
        xdh = KeyFactory.getInstance("XDH");
      } catch (NoSuchAlgorithmException e) {
        throw new AlgorithmUnavailable("XDH key factory (X25519)");
      }
      PublicKey pub =
          xdh.generatePublic(
              new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(1, be)));
      String alg = keyAgreementAlgorithm;
      KeyAgreement ka;
      try {
        ka = KeyAgreement.getInstance(alg);
      } catch (NoSuchAlgorithmException e) {
        throw new AlgorithmUnavailable(alg + " key agreement");
      }
      ka.init(priv);
      KEY_AGREEMENTS.increment();
      try {
        ka.doPhase(pub, true);
      } catch (InvalidKeyException smallOrder) {
        // The only refusal doPhase makes for a well-typed X25519 key: a small-order point.
        return null;
      }
      byte[] shared = ka.generateSecret();
      if (allZero(shared)) {
        Arrays.fill(shared, (byte) 0);
        return null;
      }
      return shared;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(
          "qf1: X25519 failed unexpectedly (" + e.getClass().getSimpleName() + ")");
    }
  }

  /**
   * AES-256-GCM decrypt with key {@code okm[0:32]} and nonce {@code okm[32:44]}. Only a failed tag
   * ({@link AEADBadTagException}) is a {@code decrypt_failed}; any other JCA error (bad key length,
   * missing provider) is a bug and surfaces as {@link IllegalStateException}, never as a qf1 class.
   */
  static byte[] gcmOpen(byte[] okm, byte[] aad, byte[] sealed) throws AEADBadTagException {
    try {
      Cipher gcm;
      try {
        gcm = Cipher.getInstance("AES/GCM/NoPadding");
      } catch (NoSuchAlgorithmException | javax.crypto.NoSuchPaddingException e) {
        throw new AlgorithmUnavailable("AES/GCM/NoPadding");
      }
      gcm.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(okm, 0, AES_KEY_LENGTH, "AES"),
          new GCMParameterSpec(TAG_BITS, okm, AES_KEY_LENGTH, OKM_LENGTH - AES_KEY_LENGTH));
      gcm.updateAAD(aad);
      return gcm.doFinal(sealed);
    } catch (AEADBadTagException e) {
      throw e;
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "qf1: AES-GCM failed unexpectedly (" + e.getClass().getSimpleName() + ")");
    }
  }

  /** HKDF-SHA256 Extract (RFC 5869), written out over HMAC-SHA256 as spec 5.3 requires. */
  static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
    return hmacSha256(salt, ikm);
  }

  /** HKDF-SHA256 Expand (RFC 5869) to {@code length} bytes. */
  static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
    byte[] okm = new byte[length];
    byte[] t = new byte[0];
    int filled = 0;
    for (int i = 1; filled < length; i++) {
      byte[] next = hmacSha256(prk, t, info, new byte[] {(byte) i});
      Arrays.fill(t, (byte) 0);
      t = next;
      int n = Math.min(t.length, length - filled);
      System.arraycopy(t, 0, okm, filled, n);
      filled += n;
    }
    Arrays.fill(t, (byte) 0);
    return okm;
  }

  /**
   * {@code OKM = HKDF(salt = E || pub_r, ikm = S, info = INFO, L = 44)}; key [0:32], nonce rest.
   */
  static byte[] deriveOkm(byte[] shared, byte[] ephemeral, byte[] recipientPublic) {
    byte[] salt = new byte[ephemeral.length + recipientPublic.length];
    System.arraycopy(ephemeral, 0, salt, 0, ephemeral.length);
    System.arraycopy(recipientPublic, 0, salt, ephemeral.length, recipientPublic.length);
    byte[] prk = hkdfExtract(salt, shared);
    try {
      return hkdfExpand(prk, HKDF_INFO, OKM_LENGTH);
    } finally {
      Arrays.fill(prk, (byte) 0);
    }
  }

  private static byte[] hmacSha256(byte[] key, byte[]... parts) {
    try {
      Mac mac;
      try {
        mac = Mac.getInstance("HmacSHA256");
      } catch (NoSuchAlgorithmException e) {
        throw new AlgorithmUnavailable("HmacSHA256");
      }
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      for (byte[] p : parts) mac.update(p);
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(
          "qf1: HmacSHA256 is unavailable (" + e.getClass().getSimpleName() + ")");
    }
  }

  /** Strict UTF-8: malformed or unmappable input is an error, never replaced; a BOM is kept. */
  static String decodeUtf8Strict(byte[] bytes) throws CharacterCodingException {
    CharsetDecoder d =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    return d.decode(ByteBuffer.wrap(bytes)).toString();
  }

  private static byte[] sha256(byte[] in) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(in);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("qf1: SHA-256 is unavailable");
    }
  }

  private static boolean allZero(byte[] b) {
    int acc = 0;
    for (byte x : b) acc |= x;
    return acc == 0;
  }

  private static byte[] basePoint() {
    byte[] u = new byte[KEY_LENGTH];
    u[0] = 9;
    return u;
  }
}
