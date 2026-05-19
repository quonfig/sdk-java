package com.quonfig.sdk.exceptions;

/**
 * Raised when a {@code decryptWith} confidential value cannot be decrypted: the key config is
 * missing/unmatched, the key value is empty, or AES-GCM decryption itself fails. Maps to the {@code
 * unable_to_decrypt} YAML error key.
 */
public final class QuonfigDecryptionException extends RuntimeException {

  public QuonfigDecryptionException(String message) {
    super(message);
  }

  public QuonfigDecryptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
