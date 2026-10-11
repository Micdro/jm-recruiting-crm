package com.janemichael.jmrecruitingcrm.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The shared tables' columns and foreign keys, read from the database itself so sync keeps working
 * as the schema grows. Loaded once; the schema doesn't change while the app runs.
 */
@Component
public class SyncCatalog {

    /** How deleting a parent affects a child row, from pg_constraint.confdeltype. */
    public enum OnDelete {
        CASCADE, SET_NULL, RESTRICT;

        static OnDelete of(String code) {
            return switch (code) {
                case "c" -> CASCADE;
                case "n", "d" -> SET_NULL;
                default -> RESTRICT;
            };
        }
    }

    public record ForeignKey(String child, String column, String parent, OnDelete onDelete) {
    }

    private final JdbcTemplate jdbc;
    private volatile Map<String, Map<String, String>> columns;
    private volatile List<ForeignKey> foreignKeys;

    public SyncCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Column name to its PostgreSQL type (for casting text back), in table order. */
    public Map<String, String> columns(String table) {
        load();
        return columns.getOrDefault(table, Map.of());
    }

    /** Foreign keys from this table to its parents. */
    public List<ForeignKey> parentsOf(String table) {
        load();
        return foreignKeys.stream().filter(key -> key.child().equals(table)).toList();
    }

    /** Foreign keys from other shared tables that point at this table. */
    public List<ForeignKey> childrenOf(String table) {
        load();
        return foreignKeys.stream().filter(key -> key.parent().equals(table)).toList();
    }

    private void load() {
        if (columns != null) {
            return;
        }
        synchronized (this) {
            if (columns != null) {
                return;
            }
            String tables = SyncTables.ORDER.stream().map(table -> "'" + table + "'").collect(Collectors.joining(","));

            Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT c.relname AS table_name, a.attname AS column_name,
                           format_type(a.atttypid, a.atttypmod) AS column_type
                    FROM pg_attribute a
                    JOIN pg_class c ON c.oid = a.attrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = current_schema() AND c.relkind = 'r'
                      AND c.relname IN (%s) AND a.attnum > 0 AND NOT a.attisdropped
                    ORDER BY c.relname, a.attnum
                    """.formatted(tables), resultSet -> {
                loaded.computeIfAbsent(resultSet.getString("table_name"), key -> new LinkedHashMap<>())
                        .put(resultSet.getString("column_name"), resultSet.getString("column_type"));
            });

            List<ForeignKey> keys = new ArrayList<>();
            jdbc.query("""
                    SELECT child.relname AS child, a.attname AS column_name, parent.relname AS parent,
                           con.confdeltype::text AS on_delete
                    FROM pg_constraint con
                    JOIN pg_class child ON child.oid = con.conrelid
                    JOIN pg_class parent ON parent.oid = con.confrelid
                    JOIN pg_namespace n ON n.oid = child.relnamespace
                    JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
                    WHERE con.contype = 'f' AND n.nspname = current_schema()
                      AND child.relname IN (%s) AND parent.relname IN (%s)
                    """.formatted(tables, tables), resultSet -> {
                keys.add(new ForeignKey(
                        resultSet.getString("child"),
                        resultSet.getString("column_name"),
                        resultSet.getString("parent"),
                        OnDelete.of(resultSet.getString("on_delete"))));
            });

            loaded.replaceAll((table, cols) -> Collections.unmodifiableMap(cols));
            foreignKeys = List.copyOf(keys);
            columns = Collections.unmodifiableMap(loaded);
        }
    }
}
