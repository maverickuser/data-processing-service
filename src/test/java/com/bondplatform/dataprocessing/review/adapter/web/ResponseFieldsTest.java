package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** U-REV-06: no read response model has a field that can carry an S3 location or source URL. */
class ResponseFieldsTest {

  /** Words of a field name that would name where data is stored or came from. */
  private static final Set<String> LOCATION_WORDS =
      Set.of("bucket", "key", "version", "url", "uri", "location", "s3", "object");

  /** Splits a camel-case name into its lower-case words: {@code errorsUrl} is errors and url. */
  private static final Pattern WORD_START = Pattern.compile("(?=[A-Z0-9])");

  /** A link to this API's own error list, not to stored data. */
  private static final Set<String> ALLOWED = Set.of("JobStatusResponse.errorsUrl");

  @Test
  void noResponseFieldNamesStorageLocations() {
    List<String> fields = new ArrayList<>();
    for (Class<?> response :
        List.of(
            JobStatusResponse.class,
            ErrorPageResponse.class,
            SecurityResponse.class,
            SummaryPageResponse.class)) {
      collect(response, fields, new HashSet<>());
    }

    assertThat(fields)
        .contains("SecurityResponse.isin", "JobStatusResponse.ErrorResponse.sourceFile");
    assertThat(fields)
        .filteredOn(field -> !ALLOWED.contains(field))
        .noneMatch(
            field ->
                WORD_START
                    .splitAsStream(field.substring(field.lastIndexOf('.') + 1))
                    .map(word -> word.toLowerCase(Locale.ROOT))
                    .anyMatch(LOCATION_WORDS::contains));
  }

  /** Lists every record component reachable from the type, through lists and nested records. */
  private static void collect(Class<?> type, List<String> fields, Set<Class<?>> seen) {
    if (!type.isRecord() || !seen.add(type)) {
      return;
    }
    String owner = type.getName().substring(type.getPackageName().length() + 1).replace('$', '.');
    for (RecordComponent component : type.getRecordComponents()) {
      fields.add(owner + "." + component.getName());
      collect(component.getType(), fields, seen);
      Type generic = component.getGenericType();
      if (generic instanceof ParameterizedType parameterized) {
        for (Type argument : parameterized.getActualTypeArguments()) {
          if (argument instanceof Class<?> element) {
            collect(element, fields, seen);
          }
        }
      }
    }
  }
}
