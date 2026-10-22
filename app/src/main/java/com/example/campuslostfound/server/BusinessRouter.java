package com.example.campuslostfound.server;

import org.json.JSONObject;
import java.util.Map;

/** A's HTTP entry calls this after parsing JSON and resolving the bearer token. */
public final class BusinessRouter {
    public static final class Reply {
        public final int status;
        public final JSONObject json;
        Reply(int status, JSONObject json) { this.status = status; this.json = json; }
    }
    private final PostHandler posts;
    private final ClaimHandler claims;
    public BusinessRouter(BusinessPort port) { posts = new PostHandler(port); claims = new ClaimHandler(port); }

    /** memberId is 0 for anonymous callers. Return null for routes owned by A. */
    public Reply route(String method, String path, Map<String, String> query, JSONObject body, long memberId) {
        if (!path.startsWith("/api/")) return null;
        String[] p = path.split("/");
        if (!isBusinessPath(p)) return null;
        if (body == null) body = new JSONObject();
        final JSONObject requestBody = body;
        try {
            JSONObject data;
            if (eq(method, "POST") && eq(path, "/api/posts")) data = posts.publish(memberId, requestBody);
            else if (eq(method, "GET") && eq(path, "/api/posts")) data = posts.list(q(query,"type"), q(query,"category"), q(query,"keyword"), q(query,"status"));
            else if (eq(method, "GET") && eq(path, "/api/me/posts")) data = posts.mine(memberId, q(query,"status"));
            else if (eq(method, "GET") && eq(path, "/api/me/claims")) data = claims.mine(memberId, q(query,"status"));
            else if (p.length == 4 && eq(p[2], "posts") && eq(method, "GET")) data = posts.get(id(p[3]));
            else if (p.length == 5 && eq(p[2], "posts") && eq(p[4], "claims") && eq(method, "POST")) data = claims.submit(memberId, id(p[3]), requestBody);
            else if (p.length == 5 && eq(p[2], "posts") && eq(p[4], "claims") && eq(method, "GET")) data = claims.received(memberId, id(p[3]), q(query,"status"));
            else if (p.length == 5 && eq(p[2], "posts") && eq(p[4], "resolve") && eq(method, "POST")) data = posts.resolve(memberId, id(p[3]));
            else if (p.length == 5 && eq(p[2], "posts") && eq(p[4], "close") && eq(method, "POST")) data = posts.close(memberId, id(p[3]));
            else if (p.length == 5 && eq(p[2], "claims") && eq(p[4], "confirm") && eq(method, "POST")) data = claims.confirm(memberId, id(p[3]));
            else if (p.length == 5 && eq(p[2], "claims") && eq(p[4], "reject") && eq(method, "POST")) data = claims.reject(memberId, id(p[3]), requestBody);
            else if (p.length == 5 && eq(p[2], "claims") && eq(p[4], "cancel") && eq(method, "POST")) data = claims.cancel(memberId, id(p[3]));
            else throw new BusinessException(404, "接口不存在");
            return new Reply(200, wrap(true, "ok", data));
        } catch (BusinessException e) { return new Reply(e.status, wrap(false, e.getMessage(), null)); }
        catch (Exception e) { return new Reply(500, wrap(false, "主机内部错误", null)); }
    }
    private static boolean isBusinessPath(String[] p) {
        return p.length > 2 && (eq(p[2], "posts") || eq(p[2], "claims") ||
                (eq(p[2], "me") && p.length > 3 && (eq(p[3], "posts") || eq(p[3], "claims"))));
    }
    private static JSONObject wrap(boolean success, String message, JSONObject data) {
        try { return new JSONObject().put("success", success).put("message", message).put("data", data == null ? JSONObject.NULL : data); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String q(Map<String,String> query, String key) { return query == null ? null : query.get(key); }
    private static boolean eq(String a, String b) { return b.equals(a); }
    private static long id(String raw) throws BusinessException {
        try { long value = Long.parseLong(raw); if (value > 0) return value; }
        catch (NumberFormatException ignored) { }
        throw new BusinessException(400, "ID 格式错误");
    }
}
