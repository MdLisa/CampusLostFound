package com.example.campuslostfound.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Private claim queries must only be called after the handler checks identity. */
public final class ClaimDao {
    private static final String SELECT = "SELECT c.*,p.title post_title,p.type post_type,p.status post_status,p.owner_id post_owner_id,m.nickname applicant_nickname FROM claims c JOIN posts p ON p.id=c.post_id JOIN members m ON m.id=c.applicant_id";

    public long insert(SQLiteDatabase db, long postId, long applicantId, String proof, String contact, long now) {
        ContentValues v = new ContentValues();
        v.put("post_id", postId); v.put("applicant_id", applicantId); v.put("proof", proof); v.put("contact", contact);
        v.put("status", "PENDING"); v.put("created_at", now); v.put("updated_at", now);
        return db.insertOrThrow("claims", null, v);
    }

    public boolean exists(SQLiteDatabase db, long postId, long applicantId) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM claims WHERE post_id=? AND applicant_id=? LIMIT 1", new String[]{Long.toString(postId), Long.toString(applicantId)})) { return c.moveToFirst(); }
    }

    public JSONObject get(SQLiteDatabase db, long id) throws JSONException {
        try (Cursor c = db.rawQuery(SELECT + " WHERE c.id=?", new String[]{Long.toString(id)})) { return c.moveToFirst() ? row(c) : null; }
    }

    public JSONObject list(SQLiteDatabase db, Long applicantId, Long postId, String status) throws JSONException {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        if (applicantId != null) { sql.append(" AND c.applicant_id=?"); args.add(Long.toString(applicantId)); }
        if (postId != null) { sql.append(" AND c.post_id=?"); args.add(Long.toString(postId)); }
        if (status != null) { sql.append(" AND c.status=?"); args.add(status); }
        sql.append(" ORDER BY c.created_at DESC,c.id DESC LIMIT 101");
        JSONArray items = new JSONArray(); boolean more = false;
        try (Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) { if (items.length() == 100) { more = true; break; } items.put(row(c)); }
        }
        return new JSONObject().put("items", items).put("hasMore", more);
    }

    public void setStatus(SQLiteDatabase db, long id, String status, String note, long now) {
        ContentValues v = new ContentValues(); v.put("status", status); v.put("note", note); v.put("updated_at", now);
        if (db.update("claims", v, "id=?", new String[]{Long.toString(id)}) != 1) throw new IllegalStateException("claim update failed");
    }

    public void rejectPending(SQLiteDatabase db, long postId, Long exceptId, String note, long now) {
        ContentValues v = new ContentValues(); v.put("status", "REJECTED"); v.put("note", note); v.put("updated_at", now);
        String where = "post_id=? AND status='PENDING'" + (exceptId == null ? "" : " AND id<>?");
        String[] args = exceptId == null ? new String[]{Long.toString(postId)} : new String[]{Long.toString(postId), Long.toString(exceptId)};
        db.update("claims", v, where, args);
    }

    private static JSONObject row(Cursor c) throws JSONException {
        String note = c.getString(c.getColumnIndexOrThrow("note"));
        return new JSONObject().put("id", c.getLong(c.getColumnIndexOrThrow("id")))
                .put("postId", c.getLong(c.getColumnIndexOrThrow("post_id")))
                .put("postTitle", c.getString(c.getColumnIndexOrThrow("post_title")))
                .put("postType", c.getString(c.getColumnIndexOrThrow("post_type")))
                .put("postStatus", c.getString(c.getColumnIndexOrThrow("post_status")))
                .put("applicant", new JSONObject().put("id", c.getLong(c.getColumnIndexOrThrow("applicant_id")))
                        .put("nickname", c.getString(c.getColumnIndexOrThrow("applicant_nickname"))))
                .put("proof", c.getString(c.getColumnIndexOrThrow("proof")))
                .put("contact", c.getString(c.getColumnIndexOrThrow("contact")))
                .put("status", c.getString(c.getColumnIndexOrThrow("status")))
                .put("note", note == null ? JSONObject.NULL : note)
                .put("createdAt", c.getLong(c.getColumnIndexOrThrow("created_at")))
                .put("updatedAt", c.getLong(c.getColumnIndexOrThrow("updated_at")));
    }
}
