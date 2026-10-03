package com.bondplatform.dataprocessing.source.domain;

import com.bondplatform.dataprocessing.contract.domain.DatasetUrn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Turns a parsed manifest object into a {@link Manifest}, or explains every way it is not one.
 *
 * <p>The input is plain maps, lists, strings, numbers, and booleans, so this class knows nothing
 * about the JSON library. Problems name a JSON Pointer into the manifest. A file hash is accepted
 * as 64 hex digits, with or without a {@code sha256:} prefix, and kept in lowercase without it.
 */
public final class ManifestReader {

  private static final String SPEC_VERSION = "1.0";
  private static final String FETCH_SERVICE = "urn:bond-platform:service:data-fetch-service";
  private static final String MANIFEST_TYPE = "com.bondplatform.dataset.manifest.v1";
  private static final Pattern SHA_256 = Pattern.compile("(sha256:)?([0-9a-fA-F]{64})");

  private ManifestReader() {}

  /** Returns the manifest, or the problems that make it unusable. */
  public static Reading read(Map<String, Object> event) {
    List<String> problems = new ArrayList<>();
    Fields root = new Fields(event, "", problems);
    root.constant("specversion", SPEC_VERSION);
    root.constant("source", FETCH_SERVICE);
    root.constant("type", MANIFEST_TYPE);
    final String id = root.text("id");
    final String dataset = root.text("dataschema");
    final String subject = root.text("subject");
    Optional<Fields> data = root.object("data");
    if (data.isEmpty()) {
      return new Reading.Unreadable(problems);
    }
    Fields fields = data.get();
    fields.schemaVersion();
    String eventType = fields.text("event_type");
    String fetchEventId = fields.text("event_id");
    String runId = fields.text("run_id");
    String fingerprint = fields.text("dataset_fingerprint");
    Map<String, String> inputs = fields.textMap("inputs");
    List<ManifestFile> files = fields.files();
    if (!problems.isEmpty()) {
      return new Reading.Unreadable(problems);
    }
    return new Reading.Read(
        new Manifest(
            required(id),
            new DatasetUrn(required(dataset)),
            required(subject),
            required(eventType),
            required(fetchEventId),
            required(runId),
            inputs,
            required(fingerprint),
            files));
  }

  private static String required(@Nullable String value) {
    if (value == null) {
      throw new IllegalStateException("A value missing without a recorded problem");
    }
    return value;
  }

  /** The outcome of reading a manifest. */
  public sealed interface Reading {

    /** The manifest is well formed. */
    record Read(Manifest manifest) implements Reading {}

    /** The manifest is not usable, for these reasons. */
    record Unreadable(List<String> problems) implements Reading {

      /** Copies the problems. */
      public Unreadable {
        problems = List.copyOf(problems);
      }
    }
  }

  /** Reads the properties of one JSON object, recording a problem for each that is wrong. */
  private record Fields(Map<String, Object> object, String pointer, List<String> problems) {

    @Nullable String text(String name) {
      Object value = object.get(name);
      if (value instanceof String text && !text.isBlank()) {
        return text;
      }
      problem(name, value == null ? "is required" : "must be non-blank text");
      return null;
    }

    @Nullable String optionalText(String name) {
      Object value = object.get(name);
      if (value == null || value instanceof String) {
        return (String) value;
      }
      problem(name, "must be text");
      return null;
    }

    void constant(String name, String expected) {
      String value = text(name);
      if (value != null && !value.equals(expected)) {
        problem(name, "must be " + expected);
      }
    }

    void schemaVersion() {
      Object value = object.get("schema_version");
      if (!(value instanceof Number number)
          || new BigDecimal(number.toString()).compareTo(BigDecimal.ONE) != 0) {
        problem("schema_version", "must be 1");
      }
    }

    Optional<Fields> object(String name) {
      Object value = object.get(name);
      if (value instanceof Map<?, ?> map) {
        return Optional.of(new Fields(typed(map), child(name), problems));
      }
      problem(name, value == null ? "is required" : "must be an object");
      return Optional.empty();
    }

    Map<String, String> textMap(String name) {
      Map<String, String> texts = new LinkedHashMap<>();
      object(name)
          .ifPresent(
              inputs ->
                  inputs.object.forEach(
                      (key, value) -> {
                        if (value instanceof String text) {
                          texts.put(key, text);
                        } else {
                          inputs.problem(key, "must be text");
                        }
                      }));
      return texts;
    }

    List<ManifestFile> files() {
      Object value = object.get("files");
      if (!(value instanceof List<?> list) || list.isEmpty()) {
        problem("files", "must list at least one file");
        return List.of();
      }
      List<ManifestFile> files = new ArrayList<>();
      for (int index = 0; index < list.size(); index++) {
        String filePointer = child("files") + "/" + index;
        if (!(list.get(index) instanceof Map<?, ?> map)) {
          problems.add(filePointer + " must be an object");
          continue;
        }
        file(new Fields(typed(map), filePointer, problems)).ifPresent(files::add);
      }
      return files;
    }

    private static Optional<ManifestFile> file(Fields file) {
      String bucket = file.text("bucket");
      String key = file.text("key");
      String formatName = file.text("format");
      Optional<SourceFormat> format =
          formatName == null ? Optional.empty() : SourceFormat.of(formatName);
      if (formatName != null && format.isEmpty()) {
        file.problem("format", "must be csv or json");
      }
      String sha256 = file.sha256();
      Long size = file.size();
      String fetchJobId = file.optionalText("job_id");
      String sourceUrl = file.optionalText("source_url");
      if (bucket == null || key == null || format.isEmpty() || sha256 == null || size == null) {
        return Optional.empty();
      }
      return Optional.of(
          new ManifestFile(fetchJobId, bucket, key, format.get(), sha256, size, sourceUrl));
    }

    @Nullable String sha256() {
      String value = text("sha256");
      if (value == null) {
        return null;
      }
      var matcher = SHA_256.matcher(value);
      if (!matcher.matches()) {
        problem("sha256", "must be 64 hex digits, optionally after sha256:");
        return null;
      }
      return matcher.group(2).toLowerCase(Locale.ROOT);
    }

    @Nullable Long size() {
      Object value = object.get("size_bytes");
      if (value instanceof Number number) {
        BigDecimal size = new BigDecimal(number.toString());
        if (size.signum() >= 0 && size.stripTrailingZeros().scale() <= 0) {
          try {
            return size.longValueExact();
          } catch (ArithmeticException e) {
            // Falls through to the problem below: too large to be a real object size.
          }
        }
      }
      problem("size_bytes", value == null ? "is required" : "must be a whole number of bytes");
      return null;
    }

    private void problem(String name, String message) {
      problems.add(child(name) + " " + message);
    }

    private String child(String name) {
      return pointer + "/" + name.replace("~", "~0").replace("/", "~1");
    }

    private static Map<String, Object> typed(Map<?, ?> map) {
      Map<String, Object> typed = new LinkedHashMap<>();
      map.forEach((key, entry) -> typed.put(String.valueOf(key), entry));
      return typed;
    }
  }
}
