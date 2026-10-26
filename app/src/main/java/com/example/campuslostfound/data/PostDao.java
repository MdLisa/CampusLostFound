package com.example.campuslostfound.data;

import android.content.ContentValues;
import com.example.campuslostfound.common.ImageRules;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Business SQL only. A owns the database helper and transaction boundary. */
public final class PostDao {
    private static final String SELECT = "SELECT p.*, m.nickname owner_nickname FROM posts p JOIN members m ON m.id=p.owner_id";

    public long insert(SQLiteDatabase db, long ownerId, JSONObject input, String imageName, long now) {
        ContentValues v = new ContentValues();
        v.put("owner_id", ownerId);
        for (String key : new String[]{"type", "title", "description", "category", "location"}) v.put(key, input.optString(key).trim());
        v.put("image_name", imageName);
        v.put("status", "OPEN");
        v.put("created_at", now);
        v.put("updated_at", now);
        long id = db.insertOrThrow("posts", null, v);
        return id;
    }

    public JSONObject get(SQLiteDatabase db, long id) throws JSONException {
        try (Cursor c = db.rawQuery(SELECT + " WHERE p.id=?", new String[]{Long.toString(id)})) {
            return c.moveToFirst() ? row(c) : null;
        }
    }

    public JSONObject list(SQLiteDatabase db, Long ownerId, String type, String category, String keyword, String status) throws JSONException {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        if (ownerId != null) add(sql, args, "p.owner_id=?", Long.toString(ownerId));
        else if (status == null) sql.append(" AND p.status IN ('OPEN','RESOLVED')");
        if (type != null) add(sql, args, "p.type=?", type);
        if (category != null) add(sql, args, "p.category=?", category);
        if (status != null) add(sql, args, "p.status=?", status);
        if (keyword != null && !keyword.isEmpty()) add(sql, args, "p.title LIKE ? ESCAPE '\\'", "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        sql.append(" ORDER BY p.created_at DESC,p.id DESC LIMIT 101");
        JSONArray items = new JSONArray();
        boolean more = false;
        try (Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) { if (items.length() == 100) { more = true; break; } items.put(row(c)); }
        }
        return new JSONObject().put("items", items).put("hasMore", more);
    }

    private static void add(StringBuilder sql, java.util.List<String> args, String test, String value) {
        sql.append(" AND ").append(test); args.add(value);
    }

    public void setStatus(SQLiteDatabase db, long id, String status, long now) {
        ContentValues v = new ContentValues(); v.put("status", status); v.put("updated_at", now);
        if (db.update("posts", v, "id=?", new String[]{Long.toString(id)}) != 1) throw new IllegalStateException("post update failed");
    }

    private static JSONObject row(Cursor c) throws JSONException {
        String image = c.getString(c.getColumnIndexOrThrow("image_name"));
        return new JSONObject().put("id", c.getLong(c.getColumnIndexOrThrow("id")))
                .put("type", c.getString(c.getColumnIndexOrThrow("type")))
                .put("title", c.getString(c.getColumnIndexOrThrow("title")))
                .put("description", c.getString(c.getColumnIndexOrThrow("description")))
                .put("category", c.getString(c.getColumnIndexOrThrow("category")))
                .put("location", c.getString(c.getColumnIndexOrThrow("location")))
                .put("imagePath", image == null ? JSONObject.NULL : ImageRules.imagePath(image))
                .put("status", c.getString(c.getColumnIndexOrThrow("status")))
                .put("owner", new JSONObject().put("id", c.getLong(c.getColumnIndexOrThrow("owner_id")))
                        .put("nickname", c.getString(c.getColumnIndexOrThrow("owner_nickname"))))
                .put("createdAt", c.getLong(c.getColumnIndexOrThrow("created_at")))
                .put("updatedAt", c.getLong(c.getColumnIndexOrThrow("updated_at")));
    }
}
