package com.example.campuslostfound.common;

/** 全工程唯一的数据库锁，所有读写通过 AppDbHelper 使用这把锁。 */
public final class DatabaseLock {
    public static final Object DB_LOCK = new Object();

    private DatabaseLock() { }
}
