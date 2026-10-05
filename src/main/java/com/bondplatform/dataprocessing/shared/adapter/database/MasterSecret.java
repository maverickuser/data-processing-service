package com.bondplatform.dataprocessing.shared.adapter.database;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The database master user's name and password, from the secret RDS manages for the instance. Only
 * the migration function reads it; the other functions log in with IAM tokens.
 *
 * @param username the master user's name
 * @param password the master user's current password
 */
public record MasterSecret(String username, String password) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Reads the secret string RDS stores: a JSON object with {@code username} and {@code password}.
   */
  public static MasterSecret parse(String secretString) {
    JsonNode secret = JSON.readTree(secretString);
    String username = secret.path("username").asString("");
    String password = secret.path("password").asString("");
    if (username.isEmpty() || password.isEmpty()) {
      throw new IllegalStateException("The master secret has no username or password");
    }
    return new MasterSecret(username, password);
  }

  /** Never shows the password. */
  @Override
  public String toString() {
    return "MasterSecret[username=" + username + "]";
  }
}
