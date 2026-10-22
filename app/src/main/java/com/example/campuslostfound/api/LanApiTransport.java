package com.example.campuslostfound.api;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** 复用 LanApiClient 的网络、token 和线程策略，适配 B 的业务回调。 */
public final class LanApiTransport implements LostFoundApi.Transport {
    private final LanApiClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public LanApiTransport(LanApiClient client) {
        this.client = client;
    }

    @Override
    public void request(String method, String path, JSONObject body, LostFoundApi.Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback 不能为空");
        }
        try {
            client.request(method, path, body, new LanApiClient.Callback() {
                @Override
                public void onSuccess(JSONObject data) {
                    callback.onResult(200, wrap(true, "ok", data), null);
                }

                @Override
                public void onFailure(LanApiClient.ApiError error) {
                    if (error.statusCode > 0) {
                        callback.onResult(error.statusCode, wrap(false, error.message, null), null);
                    } else {
                        callback.onResult(0, null, new IOException(error.message));
                    }
                }
            });
        } catch (IllegalArgumentException | IllegalStateException e) {
            mainHandler.post(() -> callback.onResult(0, null, e));
        }
    }

    private JSONObject wrap(boolean success, String message, JSONObject data) {
        try {
            return new JSONObject().put("success", success).put("message", message)
                    .put("data", data == null ? JSONObject.NULL : data);
        } catch (JSONException e) {
            throw new IllegalStateException("无法生成业务响应", e);
        }
    }
}
