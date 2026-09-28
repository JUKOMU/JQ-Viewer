package io.github.jukomu.feature.localfile.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** The released 1.4.6 schema, shared by migration and real plugin startup tests. */
public final class LegacyLocalFileDatabase {
    private LegacyLocalFileDatabase() {
    }

    public static SQLiteDatabase createVersionNine(Context context) throws Exception {
        File file = context.getDatabasePath("jq_pdf_import.db");
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create test database directory");
        }
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(file, null);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("pdf-v9-schema.sql"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && !line.startsWith("--")) database.execSQL(line);
            }
            database.setVersion(9);
            return database;
        } catch (Exception error) {
            database.close();
            throw error;
        }
    }
}
