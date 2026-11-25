package com.quonfig.sdk.eval;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Test-only qf1 writer: mints a throwaway keypair at run time and seals arbitrary plaintext bytes
 * (including invalid UTF-8, which no real writer produces). Never used by the SDK.
 */
public final class Qf1TestSeal {

  private static final SecureRandom RANDOM = new SecureRandom();

  /** A freshly minted key: the env entry ({@code qf1-priv-...}) and the raw public key. */
  public static final class Key {
    public final String privateEntry;
    public final byte[] publicKey;

    Key(String privateEntry, byte[] publicKey) {
      this.privateEntry = privateEntry;
      this.publicKey = publicKey;
    }

    public String fingerprint() {
      return Qf1Reader.fingerprint(publicKey);
    }
  }

  private Qf1TestSeal() {}

  private static byte[] basePoint() {
    byte[] u = new byte[32];
    u[0] = 9;
    return u;
  }

  public static Key mint() {
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    byte[] pub = Qf1Reader.x25519(Qf1Reader.privateKey(raw), basePoint());
    String entry = "qf1-priv-" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    return new Key(entry, pub);
  }

  /**
   * Seals {@code plaintext} to {@code recipient} under {@code keyset} (spec 7, no input checks).
   */
  public static String seal(String keyset, Key recipient, byte[] plaintext) throws Exception {
    byte[] ephRaw = new byte[32];
    RANDOM.nextBytes(ephRaw);
    java.security.PrivateKey eph = Qf1Reader.privateKey(ephRaw);
    byte[] e = Qf1Reader.x25519(eph, basePoint());
    byte[] s = Qf1Reader.x25519(eph, recipient.publicKey);
    byte[] okm = Qf1Reader.deriveOkm(s, e, recipient.publicKey);
    String header = "qf1:" + keyset + ":" + recipient.fingerprint() + ":";
    Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
    gcm.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(okm, 0, 32, "AES"),
        new GCMParameterSpec(128, okm, 32, 12));
    gcm.updateAAD(header.getBytes(StandardCharsets.US_ASCII));
    byte[] sealed = gcm.doFinal(plaintext);
    byte[] blob = new byte[32 + sealed.length];
    System.arraycopy(e, 0, blob, 0, 32);
    System.arraycopy(sealed, 0, blob, 32, sealed.length);
    return header + Base64.getUrlEncoder().withoutPadding().encodeToString(blob);
  }
}
