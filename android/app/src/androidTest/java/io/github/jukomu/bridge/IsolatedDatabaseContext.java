package io.github.jukomu.bridge;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;

final class IsolatedDatabaseContext extends ContextWrapper {

    private final File databaseDirectory;

    IsolatedDatabaseContext(Context base, File databaseDirectory) {
        super(base);
        this.databaseDirectory = databaseDirectory;
        if (!databaseDirectory.exists() && !databaseDirectory.mkdirs()) {
            throw new IllegalStateException(
                "Unable to create test database directory: " + databaseDirectory);
        }
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public File getDatabasePath(String name) {
        return new File(databaseDirectory, name);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                                               SQLiteDatabase.CursorFactory factory) {
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                                               SQLiteDatabase.CursorFactory factory,
                                               DatabaseErrorHandler errorHandler) {
        return SQLiteDatabase.openOrCreateDatabase(
            getDatabasePath(name).getPath(), factory, errorHandler);
    }

    void deleteTestDatabases() {
        File[] files = databaseDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.delete() && file.exists()) {
                    throw new IllegalStateException("Unable to delete test database: " + file);
                }
            }
        }
        if (!databaseDirectory.delete() && databaseDirectory.exists()) {
            throw new IllegalStateException(
                "Unable to delete test database directory: " + databaseDirectory);
        }
    }
}
