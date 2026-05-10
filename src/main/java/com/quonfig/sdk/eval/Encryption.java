package com.quonfig.sdk.eval;

import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM ciphertext format used by Quonfig's {@code confidential} values.
 *
 * <p>Wire format: {@code DATA--IV--AUTH_TAG}, where each segment is hex-encoded. The key is also a
 * hex string (32 bytes / AES-256 in production). Both segments and the key are hex-decoded
 * case-insensitively, mirroring sdk-go's behavior.
 */
public final class Encryption {

  private static final int GCM_TAG_BITS = 128;
  private static final int VALID_PARTS = 3;

  private Encryption() {}

  /**
   * Decrypts an AES-GCM ciphertext encoded as {@code DATA--IV--AUTH_TAG}.
   *
   * @param secretKeyHex hex-encoded AES key (16, 24, or 32 bytes after decoding)
   * @param ciphertext encrypted value in {@code DATA--IV--AUTH_TAG} format
   * @return decrypted UTF-8 string
   * @throws GeneralSecurityException if the key, IV, ciphertext, or auth tag is invalid
   * @throws IllegalArgumentException if the wire format is malformed
   */
  public static String decrypt(String secretKeyHex, String ciphertext)
      throws GeneralSecurityException {
    String[] parts = ciphertext.split("--", -1);
    if (parts.length != VALID_PARTS) {
      throw new IllegalArgumentException(
          "invalid encrypted value format: expected DATA--IV--AUTH_TAG");
    }

    HexFormat hex = HexFormat.of();
    byte[] key = hex.parseHex(secretKeyHex.toLowerCase());
    byte[] iv = hex.parseHex(parts[1].toLowerCase());
    byte[] data = hex.parseHex(parts[0].toLowerCase());
    byte[] tag = hex.parseHex(parts[2].toLowerCase());

    byte[] dataPlusTag = new byte[data.length + tag.length];
    System.arraycopy(data, 0, dataPlusTag, 0, data.length);
    System.arraycopy(tag, 0, dataPlusTag, data.length, tag.length);

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
    return new String(cipher.doFinal(dataPlusTag), java.nio.charset.StandardCharsets.UTF_8);
  }
}
