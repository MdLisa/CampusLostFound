package com.example.campuslostfound.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.campuslostfound.common.ApiConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A 提供的公共网络入口。每个页面持有一个实例，销毁时 close。
 * connect 先验证主机；后续 request 复用地址和身份。全部回调位于主线程。
 */
public final class LanApiClient implements AutoCloseable {
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final ClientConnectionStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<RequestHandle> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public interface Callback {
        /** data 就是统一响应包装中的 data 对象。 */
        void onSuccess(JSONObject data);
        void onFailure(ApiError error);
    }

    public static final class ApiError {
        /** HTTP 状态码；本地校验、网络或协议错误为 0。 */
        public final int statusCode;
        public final String message;

        private ApiError(int statusCode, String message) {
            this.statusCode = statusCode;
            this.message = message;
        }
    }

    public static final class RequestHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /** 取消后不再向页面回调；已发出的请求靠超时结束，不代表服务端操作回滚。 */
        public void cancel() {
            cancelled.set(true);
        }
    }

    public LanApiClient(Context context) {
        store = ClientConnectionStore.getInstance(context);
    }

    public RequestHandle connect(String address, Callback callback) {
        ensureOpen();
        try {
            String normalized = ServerAddress.normalize(address);
            ClientConnectionStore.Snapshot selected = store.selectAddress(normalized);
            // ping 不携带旧 token，HTTP 200 后还必须核对完整协议。
            return enqueue("GET", "/api/ping", null, selected, true, callback);
        } catch (IllegalArgumentException e) {
            return fail(callback, e.getMessage());
        }
    }

    public RequestHandle request(String method, String path, JSONObject body, Callback callback) {
        ensureOpen();
        try {
            if (!"GET".equals(method) && !"POST".equals(method)) {
                throw new IllegalArgumentException("请求方法只支持 GET 和 POST");
            }
            ServerAddress.checkPath(path);
            if ("GET".equals(method) && body != null) {
                throw new IllegalArgumentException("GET 参数请放在接口路径的查询参数中");
            }
            ClientConnectionStore.Snapshot selected = store.snapshot();
            if (!selected.verified) {
                throw new IllegalArgumentException("请先检测并连接主机");
            }
            String payload = "POST".equals(method) ? (body == null ? "{}" : body.toString()) : null;
            return enqueue(method, path, payload, selected, false, callback);
        } catch (IllegalArgumentException e) {
            return fail(callback, e.getMessage());
        }
    }

    private RequestHandle enqueue(String method, String path, String payload,
            ClientConnectionStore.Snapshot selected, boolean ping, Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback 不能为空");
        }
        RequestHandle handle = new RequestHandle();
        pending.add(handle);
        try {
            if (closed) {
                throw new RejectedExecutionException();
            }
            worker.execute(() -> {
                if (handle.cancelled.get() || closed) {
                    pending.remove(handle);
                    return;
                }
                JSONObject data = null;
                ApiError error = null;
                try {
                    if (!store.isCurrent(selected)) {
                        throw new ClientFailure(0, "主机连接已改变，请重新操作");
                    }
                    data = execute(method, path, payload, selected, ping);
                    if (ping) {
                        Object version = data.get("protocolVersion");
                        Object host = data.get("hostId");
                        if (!ApiConstants.APP_ID.equals(data.get("app"))
                                || !(version instanceof Number)
                                || ((Number) version).doubleValue() != ApiConstants.PROTOCOL_VERSION
                                || !(host instanceof String) || ((String) host).trim().isEmpty()) {
                            throw new ClientFailure(0, "主机标识或协议版本不匹配，请确认连接的是校园寻物站");
                        }
                        if (!store.confirmHost(selected, (String) host)) {
                            throw new ClientFailure(0, "主机连接已改变，请重新检测");
                        }
                    }
                } catch (ClientFailure e) {
                    error = new ApiError(e.code, e.getMessage());
                } catch (SocketTimeoutException e) {
                    error = new ApiError(0, "连接或读取超时，请确认主机运行中且两台手机可以互访");
                } catch (UnknownHostException e) {
                    error = new ApiError(0, "无法找到主机，请检查地址和网络");
                } catch (ConnectException e) {
                    error = new ApiError(0, "无法连接主机，请检查 IP、端口和主机开关");
                } catch (SecurityException e) {
                    error = new ApiError(0, "缺少网络访问权限，请在系统设置中允许局域网／附近设备权限");
                } catch (JSONException e) {
                    error = new ApiError(0, "主机返回的数据不符合接口约定");
                } catch (IOException e) {
                    error = new ApiError(0, "网络请求失败，请检查网络及主机状态");
                }
                deliver(handle, callback, selected, data, error);
            });
        } catch (RejectedExecutionException e) {
            deliver(handle, callback, null, null, new ApiError(0, "网络工具已关闭，请重新进入页面"));
        }
        return handle;
    }

    private JSONObject execute(String method, String path, String payload,
            ClientConnectionStore.Snapshot selected, boolean ping)
            throws IOException, JSONException, ClientFailure {
        HttpURLConnection connection = (HttpURLConnection) new URL(selected.address + path).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (!ping && !"/api/sessions".equals(path) && !selected.token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + selected.token);
        }
        try {
            if (payload != null) {
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                throw new ClientFailure(code == 200 ? 0 : code,
                        "主机没有返回响应内容（HTTP " + code + "）");
            }
            String text = read(stream);
            JSONObject response;
            try {
                response = new JSONObject(text);
            } catch (JSONException e) {
                if (code != 200) {
                    throw new ClientFailure(code, "主机请求失败（HTTP " + code + "），且未返回约定的 JSON");
                }
                throw e;
            }
            if (code != 200) {
                throw new ClientFailure(code, response.optString("message", "主机请求失败（HTTP " + code + "）"));
            }
            if (!Boolean.TRUE.equals(response.get("success"))) {
                throw new ClientFailure(0, response.optString("message", "主机未能完成请求"));
            }
            response.getString("message");
            return response.getJSONObject("data");
        } finally {
            connection.disconnect();
        }
    }

    private String read(InputStream stream) throws IOException, ClientFailure {
        if (stream == null) {
            throw new ClientFailure(0, "主机没有返回响应内容");
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw new ClientFailure(0, "主机返回内容过大");
                }
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    private RequestHandle fail(Callback callback, String message) {
        if (callback == null) {
            throw new IllegalArgumentException("callback 不能为空");
        }
        RequestHandle handle = new RequestHandle();
        pending.add(handle);
        deliver(handle, callback, null, null, new ApiError(0, message));
        return handle;
    }

    private void deliver(RequestHandle handle, Callback callback,
            ClientConnectionStore.Snapshot selected, JSONObject data, ApiError error) {
        mainHandler.post(() -> {
            pending.remove(handle);
            if (closed || handle.cancelled.get()) {
                return;
            }
            if (selected != null && !store.isCurrent(selected)) {
                callback.onFailure(new ApiError(0, "主机连接已改变，请重新操作"));
            } else if (error != null) {
                callback.onFailure(error);
            } else {
                callback.onSuccess(data);
            }
        });
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("网络工具已关闭，请为新页面创建实例");
        }
    }

    @Override
    public void close() {
        closed = true;
        for (RequestHandle handle : pending) {
            handle.cancel();
        }
        pending.clear();
        worker.shutdownNow();
    }

    private static final class ClientFailure extends Exception {
        final int code;

        ClientFailure(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
