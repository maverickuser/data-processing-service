package com.bondplatform.dataprocessing.review.adapter.web;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The IAM roles that may call the read routes (LLD section 23.2). API Gateway lets through only
 * SigV4-signed requests, but any role in the account whose policy allows {@code execute-api:Invoke}
 * could sign one; this list narrows that to the named roles.
 *
 * <p>A caller arrives as an assumed-role session ARN, {@code
 * arn:aws:sts::123456789012:assumed-role/Name/session}, which carries no role path, so roles are
 * matched by partition, account, and name. Role names are unique per account regardless of case, so
 * the name is compared without case. An empty list allows nobody. Names holding a comma, which IAM
 * allows, are refused, because the setting is a comma-separated list: a comma inside one entry
 * would otherwise read as a second role.
 *
 * @param roleArns role ARNs such as {@code arn:aws:iam::123456789012:role/smoke-test}
 */
@ConfigurationProperties("data-processing.api.read-callers")
public record ReadCallerProperties(List<String> roleArns) {

  /** A role ARN, optionally with a path; groups: partition, account, name. */
  private static final Pattern ROLE_ARN =
      Pattern.compile(
          "arn:(aws[a-z-]{0,20}):iam::(\\d{12}):role/(?:[\\w+=.@-]{1,128}/){0,32}"
              + "([\\w+=.@-]{1,64})");

  /** An assumed-role session ARN; groups: partition, account, role name. */
  private static final Pattern SESSION_ARN =
      Pattern.compile(
          "arn:(aws[a-z-]{0,20}):sts::(\\d{12}):assumed-role/([\\w+=.@-]{1,64})/"
              + "[\\w+=,.@-]{2,64}");

  /**
   * Checks every entry.
   *
   * @throws IllegalArgumentException if an entry is not an IAM role ARN
   */
  public ReadCallerProperties(@Nullable List<String> roleArns) {
    List<String> entries = roleArns == null ? List.of() : List.copyOf(roleArns);
    for (String entry : entries) {
      if (!ROLE_ARN.matcher(entry.strip()).matches()) {
        throw new IllegalArgumentException(
            "data-processing.api.read-callers.role-arns must hold only IAM role ARNs");
      }
    }
    this.roleArns = entries;
  }

  /** Returns the allowed roles as {@code partition:account:name}, the name in lowercase. */
  Set<String> allowedRoles() {
    Set<String> roles = new HashSet<>();
    for (String entry : roleArns) {
      Matcher match = ROLE_ARN.matcher(entry.strip());
      if (match.matches()) {
        roles.add(key(match.group(1), match.group(2), match.group(3)));
      }
    }
    return Set.copyOf(roles);
  }

  /**
   * Returns the caller's role as {@code partition:account:name}, or {@code null} if the ARN is not
   * an assumed-role session, such as an IAM user or a root caller.
   */
  static @Nullable String roleOf(@Nullable String callerArn) {
    if (callerArn == null) {
      return null;
    }
    Matcher match = SESSION_ARN.matcher(callerArn);
    return match.matches() ? key(match.group(1), match.group(2), match.group(3)) : null;
  }

  private static String key(String partition, String account, String name) {
    return partition + ":" + account + ":" + name.toLowerCase(Locale.ROOT);
  }
}
