package com.example.campuslostfound.common;

import org.json.JSONException;
import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;

/** 所有接口复用同一种 JSON 包装。 */
public final class JsonResponse {
    private JsonResponse() { }

    public static NanoHTTPD.Response success(JSONObject data) {
        return build(NanoHTTPD.Response.Status.OK, true, "ok", data);
    }

    public static NanoHTTPD.Response error(NanoHTTPD.Response.Status status, String message) {
        return build(status, false, message, null);
    }

    private static NanoHTTPD.Response build(NanoHTTPD.Response.Status status,
            boolean success, String message, JSONObject data) {
        try {
            JSONObject json = new JSONObject();
            json.put("success", success);
            json.put("message", message);
            json.put("data", data == null ? JSONObject.NULL : data);
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    status, "application/json; charset=utf-8", json.toString());
            response.addHeader("Cache-Control", "no-store");
            return response;
        } catch (JSONException e) {
            throw new IllegalStateException("无法生成 JSON 响应", e);
        }
    }
}

