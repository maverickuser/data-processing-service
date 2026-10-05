package com.bondplatform.dataprocessing.mapping.domain;

import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalField;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Chooses one value per scalar field when a request's files supply several (LLD section 13.5).
 *
 * <p>Files are taken in ascending {@code (LastModified, object key)} order and the last usable
 * value of each field wins, so the latest file wins and the full object key breaks a tie. The
 * choice is made per field: a later file with no usable value for a field leaves an earlier file's
 * value in place.
 */
public final class ScalarPrecedenceResolver {

  private static final Comparator<ScalarSource> PRECEDENCE =
      Comparator.comparing(ScalarSource::lastModified).thenComparing(ScalarSource::objectKey);

  private ScalarPrecedenceResolver() {}

  /**
   * One file's scalars and what orders the file.
   *
   * @param objectKey the file's full S3 object key
   * @param lastModified the object's S3 {@code LastModified}
   * @param scalars the file's canonical scalars, usable or not
   */
  public record ScalarSource(
      String objectKey, Instant lastModified, List<JsonCanonicalField> scalars) {

    /** Rejects a missing part and copies the list. */
    public ScalarSource {
      Objects.requireNonNull(objectKey, "objectKey");
      Objects.requireNonNull(lastModified, "lastModified");
      scalars = List.copyOf(scalars);
    }
  }

  /**
   * The winning value of one field.
   *
   * @param field the usable canonical field
   * @param objectKey the key of the file it came from
   */
  public record ResolvedScalar(JsonCanonicalField field, String objectKey) {}

  /**
   * Returns the winning usable value of each field, by canonical field name.
   *
   * <p>A field with no usable value in any file is absent.
   */
  public static Map<String, ResolvedScalar> resolve(List<ScalarSource> sources) {
    Map<String, ResolvedScalar> winners = new LinkedHashMap<>();
    sources.stream()
        .sorted(PRECEDENCE)
        .forEach(
            source ->
                source.scalars().stream()
                    .filter(JsonCanonicalField::isUsable)
                    .forEach(
                        field ->
                            winners.put(
                                field.name(), new ResolvedScalar(field, source.objectKey()))));
    return winners;
  }
}
