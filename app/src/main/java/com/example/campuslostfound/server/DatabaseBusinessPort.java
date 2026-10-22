package com.example.campuslostfound.server;

import android.database.sqlite.SQLiteDatabase;

import com.example.campuslostfound.common.DatabaseLock;
import com.example.campuslostfound.data.AppDbHelper;

/** 将 B 的业务回调接入 A 唯一的数据库入口，保留原异常以便事务回滚和正确返回状态。 */
public final class DatabaseBusinessPort implements BusinessPort {
    private final AppDbHelper helper;

    public DatabaseBusinessPort(AppDbHelper helper) {
        this.helper = helper;
    }

    @Override
    public SQLiteDatabase readableDatabase() {
        if (!Thread.holdsLock(DatabaseLock.DB_LOCK)) {
            throw new IllegalStateException("读取数据库请使用 BusinessPort.read");
        }
        return helper.getReadableDatabase();
    }

    @Override
    public <T> T read(Work<T> work) throws Exception {
        try {
            return helper.read(database -> run(work, database));
        } catch (WorkFailed e) {
            throw e.original;
        }
    }

    @Override
    public <T> T writeTransaction(Work<T> work) throws Exception {
        try {
            return helper.withWriteTransaction(database -> run(work, database));
        } catch (WorkFailed e) {
            throw e.original;
        }
    }

    private <T> T run(Work<T> work, SQLiteDatabase database) {
        try {
            return work.run(database);
        } catch (Exception e) {
            throw new WorkFailed(e);
        }
    }

    @Override
    public String saveJpeg(String base64) throws BusinessException {
        // 图片工具属于第五步；第四步先联调无图业务。
        throw new BusinessException(400, "图片功能尚未接入，请先发布无图信息");
    }

    @Override
    public void deleteJpeg(String fileName) {
        // 本步骤从不保存图片，因此没有需要清理的图片文件。
    }

    private static final class WorkFailed extends RuntimeException {
        final Exception original;

        WorkFailed(Exception original) {
            super(original);
            this.original = original;
        }
    }
}
