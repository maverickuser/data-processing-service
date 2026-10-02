package com.bondplatform.dataprocessing.contract.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A fingerprint of a contract file's content.
 *
 * <p>A job records the hash of the contracts it was accepted under, which reveals an existing
 * version that was changed in place instead of being given a new version. The hash is taken over
 * the file's bytes exactly as packaged; the repository stores contract files with LF line endings
 * on every platform (see {@code .gitattributes}) so that the same commit always gives the same
 * hash.
 */
public final class ContractHash {

  private ContractHash() {}

  /** Returns {@code sha256:} followed by the lowercase hex SHA-256 of the bytes. */
  public static String of(byte[] contractBytes) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(contractBytes);
      return "sha256:" + HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
    }
  }
}
