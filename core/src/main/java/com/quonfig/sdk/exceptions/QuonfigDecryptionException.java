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
    this(message, null, null);
  }

  private final String errorClass;

  public QuonfigDecryptionException(String message, Throwable cause) {
    this(message, cause, null);
  }

  /**
   * @param errorClass the qf1 reader error class (spec 9.1, for example {@code "key_not_held"}), or
   *     {@code null} when the failure is not a qf1 read (a value that still carries {@code
   *     decryptWith})
   */
  public QuonfigDecryptionException(String message, Throwable cause, String errorClass) {
    super(message, cause);
    this.errorClass = errorClass;
  }

  /**
   * The qf1 reader error class, for detection without parsing the message: one of {@code
   * malformed}, {@code unsupported_version}, {@code invalid_keyset_name}, {@code keyset_missing},
   * {@code keyset_invalid}, {@code key_not_held}, {@code invalid_ephemeral_key} or {@code
   * decrypt_failed}. {@code null} when the value was not a qf1 read, for example one that still
   * carries the old {@code decryptWith} field.
   */
  public String errorClass() {
    return errorClass;
  }
}
