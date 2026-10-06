package com.bondplatform.dataprocessing.shared.adapter.database;

import com.zaxxer.hikari.HikariCredentialsProvider;
import com.zaxxer.hikari.util.Credentials;
import java.net.URI;
import software.amazon.awssdk.services.rds.RdsUtilities;

/**
 * Gives the connection pool a fresh RDS IAM authentication token as the password of each new
 * connection (LLD section 23.5).
 *
 * <p>A token is signed locally with the function role's credentials, so it needs no network call,
 * and it is valid for 15 minutes, which is only long enough to open a connection. It is created
 * when the pool opens a connection, never while the function initializes, so a SnapStart snapshot
 * holds no token.
 */
public final class RdsIamCredentials implements HikariCredentialsProvider {

  private static final int DEFAULT_PORT = 5432;

  private final RdsUtilities rds;
  private final String username;
  private final String host;
  private final int port;

  /**
   * Creates the provider for one database user.
   *
   * @param rds signs tokens in the database's region
   * @param jdbcUrl the PostgreSQL JDBC URL; its host and port are what the token is for
   * @param username the database user the function role may connect as
   */
  public RdsIamCredentials(RdsUtilities rds, String jdbcUrl, String username) {
    URI address = URI.create(jdbcUrl.replaceFirst("^jdbc:", ""));
    if (address.getHost() == null) {
      throw new IllegalArgumentException("No database host in " + jdbcUrl);
    }
    this.rds = rds;
    this.username = username;
    this.host = address.getHost();
    this.port = address.getPort() == -1 ? DEFAULT_PORT : address.getPort();
  }

  @Override
  public Credentials getCredentials() {
    String token =
        rds.generateAuthenticationToken(
            request -> request.hostname(host).port(port).username(username));
    return Credentials.of(username, token);
  }
}
