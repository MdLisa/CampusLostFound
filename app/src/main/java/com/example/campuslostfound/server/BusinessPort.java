package com.example.campuslostfound.server;

import android.database.sqlite.SQLiteDatabase;

/** Adapter contract for A's single AppDbHelper, DB_LOCK and ImageStore. */
public interface BusinessPort {
    SQLiteDatabase readableDatabase();
    /** 查询和权限检查一起在 A 的公共锁内完成；默认实现兼容独立业务测试。 */
    default <T> T read(Work<T> work) throws Exception {
        return work.run(readableDatabase());
    }
    <T> T writeTransaction(Work<T> work) throws Exception;
    String saveJpeg(String base64) throws Exception;
    void deleteJpeg(String fileName);

    interface Work<T> { T run(SQLiteDatabase db) throws Exception; }
}
