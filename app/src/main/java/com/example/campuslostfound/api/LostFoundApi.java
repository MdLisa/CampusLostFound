package com.example.campuslostfound.api;

import android.net.Uri;
import org.json.JSONObject;

/** Thin business facade. A adapts LanApiClient to Transport; callbacks keep its thread policy. */
public final class LostFoundApi {
    public interface Callback { void onResult(int httpStatus, JSONObject response, Exception error); }
    public interface Transport { void request(String method, String path, JSONObject body, Callback callback); }
    private final Transport transport;
    public LostFoundApi(Transport transport) { this.transport = transport; }

    public void publishPost(JSONObject body, Callback cb) { post("/api/posts", body, cb); }
    public void listPosts(String type, String category, String keyword, String status, Callback cb) {
        StringBuilder path = new StringBuilder("/api/posts");
        append(path, "type", type); append(path, "category", category); append(path, "keyword", keyword); append(path, "status", status);
        get(path.toString(), cb);
    }
    public void getPost(long id, Callback cb) { get("/api/posts/" + id, cb); }
    public void getMyPosts(String status, Callback cb) { StringBuilder p = new StringBuilder("/api/me/posts"); append(p, "status", status); get(p.toString(), cb); }
    public void submitClaim(long postId, JSONObject body, Callback cb) { post("/api/posts/" + postId + "/claims", body, cb); }
    public void getMyClaims(String status, Callback cb) { StringBuilder p = new StringBuilder("/api/me/claims"); append(p, "status", status); get(p.toString(), cb); }
    public void getReceivedClaims(long postId, String status, Callback cb) { StringBuilder p = new StringBuilder("/api/posts/" + postId + "/claims"); append(p, "status", status); get(p.toString(), cb); }
    public void confirmClaim(long id, Callback cb) { post("/api/claims/" + id + "/confirm", new JSONObject(), cb); }
    public void rejectClaim(long id, String reason, Callback cb) { JSONObject body = new JSONObject(); try { body.put("reason", reason); } catch (Exception e) { cb.onResult(0, null, e); return; } post("/api/claims/" + id + "/reject", body, cb); }
    public void cancelClaim(long id, Callback cb) { post("/api/claims/" + id + "/cancel", new JSONObject(), cb); }
    public void resolvePost(long id, Callback cb) { post("/api/posts/" + id + "/resolve", new JSONObject(), cb); }
    public void closePost(long id, Callback cb) { post("/api/posts/" + id + "/close", new JSONObject(), cb); }

    private void get(String path, Callback cb) { transport.request("GET", path, null, cb); }
    private void post(String path, JSONObject body, Callback cb) { transport.request("POST", path, body, cb); }
    private static void append(StringBuilder path, String key, String value) {
        if (value == null) return;
        path.append(path.indexOf("?") < 0 ? '?' : '&').append(key).append('=').append(Uri.encode(value));
    }
}
