package com.example.campuslostfound.server;

import com.example.campuslostfound.common.JsonResponse;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import fi.iki.elonen.NanoHTTPD;

/** B's small bridge from A's HTTP and identity tools to the business router. */
public final class BusinessHttpAdapter {
    private final BusinessRouter router;
    private final SessionStore sessions;
    public BusinessHttpAdapter(BusinessRouter router, SessionStore sessions) {
        this.router = router; this.sessions = sessions;
    }

    /** Returns null for routes outside B's namespace. */
    public NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session) throws IOException, JSONException {
        String path = session.getUri();
        if (!(path.equals("/api/posts") || path.startsWith("/api/posts/") ||
                path.equals("/api/me/posts") || path.equals("/api/me/claims") ||
                path.startsWith("/api/claims/"))) return null;
        String method = session.getMethod().name();
        boolean publicPost = "GET".equals(method) &&
                (path.equals("/api/posts") || path.matches("/api/posts/[^/]+"));
        long memberId = publicPost ? 0 : sessions.requireMemberFromHeader(
                session.getHeaders().get("authorization")).id;
        JSONObject body = "POST".equals(method) ? JsonRequest.readObject(session) : new JSONObject();
        Map<String, String> query = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : session.getParameters().entrySet()) {
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) query.put(entry.getKey(), values.get(0));
        }
        BusinessRouter.Reply reply = router.route(method, path, query, body, memberId);
        if (reply == null) return null;
        if (reply.status == 200) return JsonResponse.success(reply.json.getJSONObject("data"));
        return JsonResponse.error(status(reply.status), reply.json.getString("message"));
    }

    private static NanoHTTPD.Response.Status status(int code) {
        switch (code) {
            case 400: return NanoHTTPD.Response.Status.BAD_REQUEST;
            case 401: return NanoHTTPD.Response.Status.UNAUTHORIZED;
            case 403: return NanoHTTPD.Response.Status.FORBIDDEN;
            case 404: return NanoHTTPD.Response.Status.NOT_FOUND;
            case 409: return NanoHTTPD.Response.Status.CONFLICT;
            case 413: return NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE;
            default: return NanoHTTPD.Response.Status.INTERNAL_ERROR;
        }
    }
}
