package com.bondplatform.dataprocessing.shared.adapter.database;

import tools.jackson.core.JacksonException;
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
   *
   * @throws IllegalStateException if the string is not such an object; the message never quotes the
   *     string, which holds the password
   */
  public static MasterSecret parse(String secretString) {
    JsonNode secret;
    try {
      secret = JSON.readTree(secretString);
    } catch (
        @SuppressWarnings("UnusedException")
        JacksonException e) {
      throw new IllegalStateException("The master secret is not a JSON object");
    }
    String username = secret.path("username").asString("");
    String password = secret.path("password").asString("");
    if (username.isBlank() || password.isBlank()) {
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
