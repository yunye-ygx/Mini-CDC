package com.yunye.mncdc.ddl;

import com.yunye.mncdc.model.QualifiedTable;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class SchemaChangeClassifier {

    public SchemaChange classify(QualifiedTable table, String sql) {
        if (table == null || sql == null || sql.isBlank()) {
            return null;
        }
        String normalized = sql.trim().toUpperCase(Locale.ROOT);
        if ("BEGIN".equals(normalized) || "COMMIT".equals(normalized)) {
            return null;
        }
        if (normalized.startsWith("ALTER TABLE") && normalized.contains(" DROP COLUMN ")) {
            return new SchemaChange(table, "DROP_COLUMN", true, sql);
        }
        if (normalized.startsWith("ALTER TABLE") && normalized.contains(" RENAME COLUMN ")) {
            return new SchemaChange(table, "RENAME_COLUMN", true, sql);
        }
        if (normalized.startsWith("ALTER TABLE") && normalized.contains(" MODIFY COLUMN ")) {
            return new SchemaChange(table, "MODIFY_COLUMN", true, sql);
        }
        if (normalized.startsWith("ALTER TABLE") && normalized.contains(" ADD COLUMN ")) {
            return new SchemaChange(table, "ADD_COLUMN", false, sql);
        }
        return null;
    }

    public record SchemaChange(
            QualifiedTable table,
            String ddlType,
            boolean destructive,
            String rawSql
    ) {
    }
}
