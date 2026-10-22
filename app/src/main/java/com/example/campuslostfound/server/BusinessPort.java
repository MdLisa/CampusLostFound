package com.example.campuslostfound.server;

import android.database.sqlite.SQLiteDatabase;

/** Adapter contract for A's single AppDbHelper, DB_LOCK and ImageStore. */
public interface BusinessPort {
    SQLiteDatabase readableDatabase();
    <T> T writeTransaction(Work<T> work) throws Exception;
    String saveJpeg(String base64) throws Exception;
    void deleteJpeg(String fileName);

    interface Work<T> { T run(SQLiteDatabase db) throws Exception; }
}
