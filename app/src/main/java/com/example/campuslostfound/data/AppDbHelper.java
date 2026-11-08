package com.example.campuslostfound.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Looper;

import com.example.campuslostfound.common.DatabaseLock;

/** 主机端唯一的数据库入口。只在启动主机及处理请求时打开数据库。 */
public final class AppDbHelper extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "campus_lost_found.db";
    public static final int DATABASE_VERSION = 3;
    private static AppDbHelper instance;

    private AppDbHelper(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    public static synchronized AppDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new AppDbHelper(context);
        }
        return instance;
    }

    @FunctionalInterface
    public interface DatabaseWork<T> {
        T run(SQLiteDatabase database);
    }

    /** 在锁内完成查询并关闭 Cursor；不要把 Cursor 交给锁外或界面使用。 */
    public <T> T read(DatabaseWork<T> work) {
        requireBackgroundThread();
        synchronized (DatabaseLock.DB_LOCK) {
            return work.run(getReadableDatabase());
        }
    }

    /** 任一步骤抛异常都会回滚；B 的多步状态更新共用此入口。 */
    public <T> T withWriteTransaction(DatabaseWork<T> work) {
        requireBackgroundThread();
        synchronized (DatabaseLock.DB_LOCK) {
            SQLiteDatabase database = getWritableDatabase();
            database.beginTransaction();
            try {
                T result = work.run(database);
                database.setTransactionSuccessful();
                return result;
            } finally {
                database.endTransaction();
            }
        }
    }

    public void initialize() {
        read(database -> null);
    }

    private void requireBackgroundThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("数据库操作必须在后台线程执行");
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase database) {
        super.onConfigure(database);
        database.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE members ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "nickname TEXT NOT NULL,"
                + "token_hash TEXT NOT NULL UNIQUE,"
                + "created_at INTEGER NOT NULL)");
        database.execSQL("CREATE TABLE posts ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "owner_id INTEGER NOT NULL REFERENCES members(id),"
                + "type TEXT NOT NULL CHECK(type IN ('LOST','FOUND')),"
                + "title TEXT NOT NULL,description TEXT NOT NULL,"
                + "category TEXT NOT NULL CHECK(category IN "
                + "('CARD','ELECTRONICS','KEYS','BOOKS','DAILY','OTHER')),"
                + "location TEXT NOT NULL,image_name TEXT,"
                + "status TEXT NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED','CLOSED')),"
                + "created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        database.execSQL("CREATE TABLE claims ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "post_id INTEGER NOT NULL REFERENCES posts(id),"
                + "applicant_id INTEGER NOT NULL REFERENCES members(id),"
                + "proof TEXT NOT NULL,contact TEXT NOT NULL,"
                + "status TEXT NOT NULL DEFAULT 'PENDING' "
                + "CHECK(status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED')),"
                + "note TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,"
                + "UNIQUE(post_id,applicant_id))");
        database.execSQL("CREATE INDEX idx_posts_owner ON posts(owner_id)");
        database.execSQL("CREATE INDEX idx_posts_status_created ON posts(status,created_at,id)");
        database.execSQL("CREATE INDEX idx_claims_post_status ON claims(post_id,status)");
        database.execSQL("CREATE INDEX idx_claims_applicant_created ON claims(applicant_id,created_at,id)");
        createAdminActions(database);
        addSoftDeletion(database);
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion < 2) createAdminActions(database);
        if (oldVersion < 3) addSoftDeletion(database);
    }

    private static void createAdminActions(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE admin_actions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "post_id INTEGER NOT NULL REFERENCES posts(id),"
                + "post_title TEXT NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL)");
        database.execSQL("CREATE INDEX idx_admin_actions_created ON admin_actions(created_at,id)");
    }

    private static void addSoftDeletion(SQLiteDatabase database) {
        database.execSQL("ALTER TABLE posts ADD COLUMN is_deleted INTEGER NOT NULL DEFAULT 0 CHECK(is_deleted IN (0,1))");
        database.execSQL("ALTER TABLE posts ADD COLUMN deleted_at INTEGER");
        database.execSQL("ALTER TABLE admin_actions ADD COLUMN action TEXT NOT NULL DEFAULT 'CLOSE'");
        database.execSQL("CREATE INDEX idx_posts_visible_status ON posts(is_deleted,status,created_at,id)");
    }
}
