package com.yunye.mncdc.snapshot;

import com.yunye.mncdc.model.TableMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class SnapshotQueryBuilder {

    private SnapshotQueryBuilder() {
    }

    public static QuerySpec build(TableMetadata metadata, Map<String, Object> cursor, int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be greater than 0.");
        }

        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder()
                .append("SELECT ")
                .append(joinQuoted(metadata.columns()))
                .append(" FROM ")
                .append(quoteQualifiedName(metadata.database(), metadata.table()));

        if (cursor != null && !cursor.isEmpty()) {
            sql.append(" WHERE ").append(buildPagingPredicate(metadata.primaryKeys(), cursor, parameters));
        }

        sql.append(" ORDER BY ").append(joinQuoted(metadata.primaryKeys())).append(" LIMIT ?");
        parameters.add(pageSize);
        return new QuerySpec(sql.toString(), List.copyOf(parameters));
    }

    private static String buildPagingPredicate(List<String> primaryKeys, Map<String, Object> cursor, List<Object> parameters) {
        List<String> disjuncts = new ArrayList<>();
        for (int i = 0; i < primaryKeys.size(); i++) {
            StringJoiner conjunction = new StringJoiner(" AND ", "(", ")");
            for (int j = 0; j < i; j++) {
                String previousPrimaryKey = primaryKeys.get(j);
                conjunction.add(quoteIdentifier(previousPrimaryKey) + " = ?");
                parameters.add(requireCursorValue(cursor, previousPrimaryKey));
            }

            String currentPrimaryKey = primaryKeys.get(i);
            conjunction.add(quoteIdentifier(currentPrimaryKey) + " > ?");
            parameters.add(requireCursorValue(cursor, currentPrimaryKey));
            disjuncts.add(conjunction.toString());
        }
        return String.join(" OR ", disjuncts);
    }

    private static Object requireCursorValue(Map<String, Object> cursor, String primaryKey) {
        if (!cursor.containsKey(primaryKey)) {
            throw new IllegalArgumentException("Missing cursor value for primary key: " + primaryKey);
        }
        return cursor.get(primaryKey);
    }

    private static String joinQuoted(List<String> identifiers) {
        return identifiers.stream()
                .map(SnapshotQueryBuilder::quoteIdentifier)
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new IllegalArgumentException("Identifiers must not be empty."));
    }

    private static String quoteQualifiedName(String database, String table) {
        return quoteIdentifier(database) + "." + quoteIdentifier(table);
    }

    private static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    public record QuerySpec(String sql, List<Object> parameters) {
    }
}
