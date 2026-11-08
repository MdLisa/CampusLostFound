package com.example.campuslostfound.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** 仅供主机本地管理页面使用，不注册 HTTP 路由；所有读写复用公共锁。 */
public final class HostAdminRepository {
    private final AppDbHelper helper;
    private final PostDao posts = new PostDao();
    private final ClaimDao claims = new ClaimDao();
    public HostAdminRepository(AppDbHelper helper) { this.helper = helper; }

    public JSONObject overview() {
        return helper.read(db -> {
            try {
                return new JSONObject().put("members", count(db, "SELECT COUNT(*) FROM members"))
                        .put("posts", count(db, "SELECT COUNT(*) FROM posts"))
                        .put("deleted", count(db, "SELECT COUNT(*) FROM posts WHERE is_deleted=1"))
                        .put("open", count(db, "SELECT COUNT(*) FROM posts WHERE is_deleted=0 AND status='OPEN'"))
                        .put("resolved", count(db, "SELECT COUNT(*) FROM posts WHERE is_deleted=0 AND status='RESOLVED'"))
                        .put("closed", count(db, "SELECT COUNT(*) FROM posts WHERE is_deleted=0 AND status='CLOSED'"))
                        .put("pending", count(db, "SELECT COUNT(*) FROM claims WHERE status='PENDING'"));
            } catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    private static long count(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) { c.moveToFirst(); return c.getLong(0); }
    }

    public JSONObject listPosts(String type, String category, String status, String keyword, Long owner) {
        return helper.read(db -> {
            StringBuilder sql = new StringBuilder("SELECT id FROM posts WHERE 1=1");
            List<String> args = new ArrayList<>();
            filter(sql, args, "type", type); filter(sql, args, "category", category);
            if ("DELETED".equals(status)) sql.append(" AND is_deleted=1");
            else if (status != null) { sql.append(" AND is_deleted=0"); filter(sql, args, "status", status); }
            if (owner != null) filter(sql, args, "owner_id", Long.toString(owner));
            if (keyword != null && !keyword.trim().isEmpty()) {
                sql.append(" AND title LIKE ? ESCAPE '\\'");
                args.add(like(keyword.trim()));
            }
            sql.append(" ORDER BY created_at DESC,id DESC LIMIT 101");
            JSONArray items = new JSONArray();
            boolean more = false;
            try (Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]))) {
                while (c.moveToNext()) {
                    if (items.length() == 100) { more = true; break; }
                    items.put(posts.getForAdmin(db, c.getLong(0)));
                }
                return new JSONObject().put("items", items).put("hasMore", more);
            } catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    public JSONObject getPost(long id) {
        return helper.read(db -> {
            try {
                JSONObject post = posts.getForAdmin(db, id);
                if (post == null) throw new IllegalArgumentException("信息不存在，请刷新列表。");
                return post;
            } catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    public JSONObject listMembers(String keyword) {
        return helper.read(db -> {
            JSONArray items = new JSONArray();
            String where = keyword == null || keyword.trim().isEmpty() ? ""
                    : " WHERE nickname LIKE ? ESCAPE '\\' OR CAST(id AS TEXT)=?";
            String[] args = where.isEmpty() ? null : new String[]{like(keyword.trim()), keyword.trim()};
            boolean more = false;
            try (Cursor c = db.rawQuery("SELECT id,nickname,created_at,"
                    + "(SELECT COUNT(*) FROM posts WHERE owner_id=members.id),"
                    + "(SELECT COUNT(*) FROM claims WHERE applicant_id=members.id)"
                    + " FROM members" + where + " ORDER BY created_at DESC,id DESC LIMIT 101", args)) {
                while (c.moveToNext()) {
                    if (items.length() == 100) { more = true; break; }
                    items.put(new JSONObject().put("id", c.getLong(0)).put("nickname", c.getString(1))
                            .put("createdAt", c.getLong(2)).put("postCount", c.getLong(3))
                            .put("claimCount", c.getLong(4)));
                }
                return new JSONObject().put("items", items).put("hasMore", more);
            } catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    /** 管理页面只显示申请状态，不额外暴露证明、联系方式或身份凭据。 */
    public JSONObject memberClaims(long memberId) {
        return helper.read(db -> {
            try {
                JSONObject result = claims.list(db, memberId, null, null);
                JSONArray items = result.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    items.getJSONObject(i).remove("proof"); items.getJSONObject(i).remove("contact");
                }
                return result;
            } catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    public JSONObject logs() {
        return helper.read(db -> {
            JSONArray items = new JSONArray();
            boolean more = false;
            try (Cursor c = db.rawQuery("SELECT id,post_id,post_title,reason,created_at,action"
                    + " FROM admin_actions ORDER BY created_at DESC,id DESC LIMIT 101", null)) {
                while (c.moveToNext()) {
                    if (items.length() == 100) { more = true; break; }
                    items.put(new JSONObject().put("id", c.getLong(0)).put("postId", c.getLong(1))
                            .put("postTitle", c.getString(2)).put("reason", c.getString(3))
                            .put("createdAt", c.getLong(4)).put("action", c.getString(5)));
                }
                return new JSONObject().put("items", items).put("hasMore", more);
            } catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    public void closePost(long postId, String rawReason) {
        String reason = rawReason == null ? "" : rawReason.trim();
        int length = reason.codePointCount(0, reason.length());
        if (length < 2 || length > 100) throw new IllegalArgumentException("关闭原因请填写 2～100 个字符。");
        helper.withWriteTransaction(db -> {
            try {
                JSONObject post = posts.getForAdmin(db, postId);
                if (post == null) throw new IllegalArgumentException("信息不存在，请刷新列表。");
                if (post.optBoolean("deleted") || !"OPEN".equals(post.getString("status")))
                    throw new IllegalArgumentException("信息已完成或已关闭，请刷新后查看。");
                long now = System.currentTimeMillis();
                posts.setStatus(db, postId, "CLOSED", now);
                claims.rejectPending(db, postId, null, "管理员关闭信息：" + reason, now);
                ContentValues log = new ContentValues();
                log.put("post_id", postId); log.put("post_title", post.getString("title"));
                log.put("reason", reason); log.put("created_at", now); log.put("action", "CLOSE");
                db.insertOrThrow("admin_actions", null, log);
                return null;
            } catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    /** 只标记删除：用户内容/图片停止公开，原记录和历史申请保留以便追溯。 */
    public void deletePost(long postId, String rawReason) {
        String reason = rawReason == null ? "" : rawReason.trim();
        int length = reason.codePointCount(0, reason.length());
        if (length < 2 || length > 100) throw new IllegalArgumentException("删除原因请填写 2～100 个字符。");
        helper.withWriteTransaction(db -> {
            try {
                JSONObject post = posts.getForAdmin(db, postId);
                if (post == null) throw new IllegalArgumentException("信息不存在，请刷新列表。");
                if (post.optBoolean("deleted")) throw new IllegalArgumentException("信息已经删除，请刷新后查看。");
                long now = System.currentTimeMillis();
                ContentValues values = new ContentValues();
                values.put("is_deleted", 1); values.put("deleted_at", now); values.put("updated_at", now);
                if ("OPEN".equals(post.getString("status"))) values.put("status", "CLOSED");
                if (db.update("posts", values, "id=? AND is_deleted=0",
                        new String[]{Long.toString(postId)}) != 1) throw new IllegalArgumentException("信息已经删除。");
                claims.rejectPending(db, postId, null, "管理员删除信息：" + reason, now);
                ContentValues log = new ContentValues();
                log.put("post_id", postId); log.put("post_title", post.getString("title"));
                log.put("reason", reason); log.put("created_at", now); log.put("action", "DELETE");
                db.insertOrThrow("admin_actions", null, log);
                return null;
            } catch (JSONException e) { throw new IllegalStateException(e); }
        });
    }

    private static void filter(StringBuilder sql, List<String> args, String column, String value) {
        if (value != null) { sql.append(" AND ").append(column).append("=?"); args.add(value); }
    }
    private static String like(String value) {
        return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
