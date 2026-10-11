package com.janemichael.jmrecruitingcrm.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Stores a small set of enum values (for example a company's segments) as "A,B" in one column. */
public final class EnumCsv {

    private EnumCsv() {
    }

    public static <E extends Enum<E>> String join(Collection<E> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .filter(value -> value != null)
                .distinct()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }

    public static <E extends Enum<E>> List<E> split(String column, Class<E> type) {
        List<E> values = new ArrayList<>();
        if (column == null || column.isBlank()) {
            return values;
        }
        for (String part : column.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                values.add(Enum.valueOf(type, name));
            } catch (IllegalArgumentException ignored) {
                // A value that no longer exists is dropped rather than failing the whole record.
            }
        }
        return values;
    }
}
