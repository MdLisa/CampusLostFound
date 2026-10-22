package com.example.campuslostfound.server;

import com.example.campuslostfound.data.AppDbHelper;

/** B adapter: every read/write goes through A's one helper and DB_LOCK. */
public final class AppDbBusinessPort implements BusinessPort {
    public interface Images {
        String saveJpeg(String base64) throws Exception;
        void deleteJpeg(String fileName);
    }

    private final AppDbHelper helper;
    private final Images images;
    public AppDbBusinessPort(AppDbHelper helper, Images images) {
        this.helper = helper;
        this.images = images;
    }

    @Override public <T> T read(Work<T> work) throws Exception {
        try { return helper.read(db -> run(work, db)); }
        catch (BridgeException e) { throw e.original; }
    }

    @Override public <T> T writeTransaction(Work<T> work) throws Exception {
        try { return helper.withWriteTransaction(db -> run(work, db)); }
        catch (BridgeException e) { throw e.original; }
    }

    @Override public String saveJpeg(String base64) throws Exception {
        if (images == null) throw new BusinessException(500, "图片服务尚未接入");
        return images.saveJpeg(base64);
    }

    @Override public void deleteJpeg(String fileName) {
        if (images != null) images.deleteJpeg(fileName);
    }

    private static <T> T run(Work<T> work, android.database.sqlite.SQLiteDatabase db) {
        try { return work.run(db); }
        catch (Exception e) { throw new BridgeException(e); }
    }
    private static final class BridgeException extends RuntimeException {
        final Exception original;
        BridgeException(Exception original) { super(original); this.original = original; }
    }
}
