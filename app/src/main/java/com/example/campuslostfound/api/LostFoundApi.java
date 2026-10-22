package com.example.campuslostfound.api;

import android.net.Uri;
import org.json.JSONException;
import org.json.JSONObject;

/** Business calls for UI; A's LanApiClient owns address, token, worker and main-thread callbacks. */
public final class LostFoundApi {
    private final LanApiClient client;
    public LostFoundApi(LanApiClient client) { this.client = client; }

    public LanApiClient.RequestHandle publishPost(JSONObject body, LanApiClient.Callback cb) { return post("/api/posts", body, cb); }
    public LanApiClient.RequestHandle listPosts(String type, String category, String keyword, String status, LanApiClient.Callback cb) {
        StringBuilder path = new StringBuilder("/api/posts");
        append(path, "type", type); append(path, "category", category); append(path, "keyword", keyword); append(path, "status", status);
        return get(path.toString(), cb);
    }
    public LanApiClient.RequestHandle getPost(long id, LanApiClient.Callback cb) { return get("/api/posts/" + id, cb); }
    public LanApiClient.RequestHandle getMyPosts(String status, LanApiClient.Callback cb) { StringBuilder p = new StringBuilder("/api/me/posts"); append(p, "status", status); return get(p.toString(), cb); }
    public LanApiClient.RequestHandle submitClaim(long postId, JSONObject body, LanApiClient.Callback cb) { return post("/api/posts/" + postId + "/claims", body, cb); }
    public LanApiClient.RequestHandle getMyClaims(String status, LanApiClient.Callback cb) { StringBuilder p = new StringBuilder("/api/me/claims"); append(p, "status", status); return get(p.toString(), cb); }
    public LanApiClient.RequestHandle getReceivedClaims(long postId, String status, LanApiClient.Callback cb) { StringBuilder p = new StringBuilder("/api/posts/" + postId + "/claims"); append(p, "status", status); return get(p.toString(), cb); }
    public LanApiClient.RequestHandle confirmClaim(long id, LanApiClient.Callback cb) { return post("/api/claims/" + id + "/confirm", new JSONObject(), cb); }
    public LanApiClient.RequestHandle rejectClaim(long id, String reason, LanApiClient.Callback cb) {
        try { return post("/api/claims/" + id + "/reject", new JSONObject().put("reason", reason), cb); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
    public LanApiClient.RequestHandle cancelClaim(long id, LanApiClient.Callback cb) { return post("/api/claims/" + id + "/cancel", new JSONObject(), cb); }
    public LanApiClient.RequestHandle resolvePost(long id, LanApiClient.Callback cb) { return post("/api/posts/" + id + "/resolve", new JSONObject(), cb); }
    public LanApiClient.RequestHandle closePost(long id, LanApiClient.Callback cb) { return post("/api/posts/" + id + "/close", new JSONObject(), cb); }

    private LanApiClient.RequestHandle get(String path, LanApiClient.Callback cb) { return client.request("GET", path, null, cb); }
    private LanApiClient.RequestHandle post(String path, JSONObject body, LanApiClient.Callback cb) { return client.request("POST", path, body, cb); }
    private static void append(StringBuilder path, String key, String value) {
        if (value == null) return;
        path.append(path.indexOf("?") < 0 ? '?' : '&').append(key).append('=').append(Uri.encode(value));
    }
}
