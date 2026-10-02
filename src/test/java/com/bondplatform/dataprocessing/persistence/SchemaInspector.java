package com.bondplatform.dataprocessing.persistence;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the actual shape of the database, so schema tests can compare it with the design. */
final class SchemaInspector {

  private final JdbcClient jdbc;

  SchemaInspector(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Returns the table names of a schema, in name order. */
  List<String> tables(String schema) {
    return jdbc.sql(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = ?"
                + " ORDER BY table_name")
        .param(schema)
        .query(String.class)
        .list();
  }

  /**
   * Returns one line per column, in table order: name, type, nullability, and default. A numeric
   * column with a declared precision or scale shows it, so {@code numeric} here means unbounded.
   */
  List<String> columns(String schema, String table) {
    return jdbc.sql(
            """
            SELECT column_name || ' ' || data_type
              || CASE WHEN numeric_precision IS NOT NULL AND data_type = 'numeric'
                   THEN '(' || numeric_precision || ',' || numeric_scale || ')' ELSE '' END
              || CASE WHEN is_nullable = 'NO' THEN ' NOT NULL' ELSE '' END
              || CASE WHEN column_default IS NOT NULL THEN ' DEFAULT ' || column_default ELSE '' END
              || CASE WHEN is_identity = 'YES' THEN ' IDENTITY' ELSE '' END
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """)
        .params(schema, table)
        .query(String.class)
        .list();
  }

  /** Returns the definition of every index on a table, in name order. */
  List<String> indexes(String schema, String table) {
    return jdbc.sql(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ?"
                + " ORDER BY indexname")
        .params(schema, table)
        .query(String.class)
        .list();
  }

  /** Returns every foreign key of a schema as {@code table.column -> table.column ON DELETE x}. */
  List<String> foreignKeys(String schema) {
    return jdbc.sql(
            """
            SELECT child.relname || '.' || child_column.attname || ' -> ' || parent.relname || '.'
              || parent_column.attname || ' ON DELETE '
              || CASE confdeltype WHEN 'r' THEN 'RESTRICT' WHEN 'c' THEN 'CASCADE'
                   WHEN 'a' THEN 'NO ACTION' ELSE confdeltype::text END
            FROM pg_constraint
            JOIN pg_class child ON child.oid = conrelid
            JOIN pg_class parent ON parent.oid = confrelid
            JOIN pg_namespace ON pg_namespace.oid = child.relnamespace
            JOIN pg_attribute child_column
              ON child_column.attrelid = conrelid AND child_column.attnum = conkey[1]
            JOIN pg_attribute parent_column
              ON parent_column.attrelid = confrelid AND parent_column.attnum = confkey[1]
            WHERE contype = 'f' AND nspname = ?
            ORDER BY 1
            """)
        .param(schema)
        .query(String.class)
        .list();
  }
}
