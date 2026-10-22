package com.example.campuslostfound.server;

import com.example.campuslostfound.data.ClaimDao;
import com.example.campuslostfound.data.PostDao;
import org.json.JSONObject;

/** B01-B04, B11-B12. The HTTP router supplies a verified member ID, never a request-body ID. */
public final class PostHandler {
    private final BusinessPort port;
    private final PostDao posts = new PostDao();
    private final ClaimDao claims = new ClaimDao();
    public PostHandler(BusinessPort port) { this.port = port; }

    public JSONObject publish(long memberId, JSONObject body) throws Exception {
        if (memberId <= 0) throw new BusinessException(401, "请先登录");
        enumValue(body.optString("type"), "LOST", "FOUND");
        enumValue(body.optString("category"), "CARD", "ELECTRONICS", "KEYS", "BOOKS", "DAILY", "OTHER");
        field(body, "title", 2, 40); field(body, "description", 5, 500); field(body, "location", 2, 60);
        String image = null;
        if (!body.isNull("imageBase64") && body.has("imageBase64")) {
            Object rawImage = body.opt("imageBase64");
            if (!(rawImage instanceof String)) throw new BusinessException(400, "图片格式错误");
            String base64 = (String) rawImage;
            if (base64.isEmpty()) throw new BusinessException(400, "图片格式错误");
            if (base64.length() > 700000) throw new BusinessException(413, "图片过大");
            // A's ImageStore validates JPEG bytes, decoded size and randomized filename.
            image = port.saveJpeg(base64);
        }
        final String saved = image;
        try {
            return port.writeTransaction(db -> posts.get(db, posts.insert(db, memberId, body, saved, System.currentTimeMillis())));
        } catch (Exception e) { if (saved != null) port.deleteJpeg(saved); throw e; }
    }

    public JSONObject list(String type, String category, String keyword, String status) throws Exception {
        optional(type, "LOST", "FOUND");
        optional(category, "CARD", "ELECTRONICS", "KEYS", "BOOKS", "DAILY", "OTHER");
        optional(status, "OPEN", "RESOLVED");
        if (keyword != null) { keyword = keyword.trim(); if (keyword.length() > 40) throw new BusinessException(400, "关键词过长"); }
        return posts.list(port.readableDatabase(), null, type, category, keyword, status);
    }

    public JSONObject get(long id) throws Exception {
        JSONObject post = posts.get(port.readableDatabase(), id);
        if (post == null) throw new BusinessException(404, "帖子不存在");
        return post;
    }

    public JSONObject mine(long memberId, String status) throws Exception {
        requireMember(memberId); optional(status, "OPEN", "RESOLVED", "CLOSED");
        return posts.list(port.readableDatabase(), memberId, null, null, null, status);
    }

    public JSONObject resolve(long memberId, long postId) throws Exception { return finish(memberId, postId, "RESOLVED", "发布者已通过其他途径完成交接"); }
    public JSONObject close(long memberId, long postId) throws Exception { return finish(memberId, postId, "CLOSED", "发布者已关闭信息"); }

    private JSONObject finish(long memberId, long postId, String status, String note) throws Exception {
        requireMember(memberId);
        return port.writeTransaction(db -> {
            JSONObject post = posts.get(db, postId);
            if (post == null) throw new BusinessException(404, "帖子不存在");
            if (post.getJSONObject("owner").getLong("id") != memberId) throw new BusinessException(403, "无权操作该帖子");
            if (!"OPEN".equals(post.getString("status"))) throw new BusinessException(409, "帖子状态不允许操作");
            long now = System.currentTimeMillis();
            posts.setStatus(db, postId, status, now);
            claims.rejectPending(db, postId, null, note, now);
            return posts.get(db, postId);
        });
    }

    static void requireMember(long id) throws BusinessException { if (id <= 0) throw new BusinessException(401, "请先登录"); }
    static String field(JSONObject body, String key, int min, int max) throws BusinessException {
        Object raw = body.opt(key);
        if (!(raw instanceof String)) throw new BusinessException(400, key + " 格式错误");
        String value = ((String) raw).trim();
        if (value.length() < min || value.length() > max) throw new BusinessException(400, key + " 长度不符合要求");
        return value;
    }
    static void enumValue(String value, String... allowed) throws BusinessException {
        for (String item : allowed) if (item.equals(value)) return;
        throw new BusinessException(400, "枚举字段无效");
    }
    static void optional(String value, String... allowed) throws BusinessException { if (value != null) enumValue(value, allowed); }
}
