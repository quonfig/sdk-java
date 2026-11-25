package com.quonfig.sdk.exceptions;

/**
 * Raised when a secret cannot be decrypted. The message carries the qf1 error class ({@code qf1
 * keyset_missing: ...}, {@code qf1 key_not_held: ...} and the other reader classes of the qf1 spec)
 * and names the keyset and fingerprints involved, never key material, the ciphertext or the
 * plaintext. A value that still carries the old {@code decryptWith} field also raises it: this SDK
 * no longer reads that format. Maps to the {@code unable_to_decrypt} YAML error key.
 */
public final class QuonfigDecryptionException extends RuntimeException {

  public QuonfigDecryptionException(String message) {
    super(message);
  }

  public QuonfigDecryptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
