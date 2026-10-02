package com.bondplatform.dataprocessing.contract.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A fingerprint of a contract file's content.
 *
 * <p>A job records the hash of the contracts it was accepted under, which reveals an existing
 * version that was changed in place instead of being given a new version.
 */
public final class ContractHash {

  private ContractHash() {}

  /** Returns {@code sha256:} followed by the lowercase hex SHA-256 of the text's UTF-8 bytes. */
  public static String of(String contractText) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(contractText.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
    }
  }
}
