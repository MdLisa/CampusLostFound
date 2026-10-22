package com.example.campuslostfound.server;

import com.example.campuslostfound.common.ApiException;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** 直接有界读取，避免 parseBody 先把超大请求全部写入内存或临时文件。 */
public final class JsonRequest {
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    private JsonRequest() { }

    public static JSONObject readObject(NanoHTTPD.IHTTPSession session) throws IOException {
        Map<String, String> headers = session.getHeaders();
        if (headers.containsKey("transfer-encoding")) {
            throw badRequest("请使用带 Content-Length 的 JSON 请求");
        }
        String contentType = headers.get("content-type");
        if (contentType != null && !"application/json".equals(
                contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))) {
            throw badRequest("请求内容必须为 JSON");
        }
        long length;
        try {
            String header = headers.get("content-length");
            if (header == null || !header.matches("[0-9]+")) {
                throw badRequest("缺少或无效的 Content-Length");
            }
            length = Long.parseLong(header);
        } catch (NumberFormatException e) {
            throw new ApiException(NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE, "请求体不能超过 1 MB");
        }
        if (length > MAX_BODY_BYTES) {
            throw new ApiException(NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE, "请求体不能超过 1 MB");
        }
        if (length == 0) {
            throw badRequest("请求体必须为 JSON 对象");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(length, 4096));
        InputStream input = session.getInputStream();
        byte[] buffer = new byte[4096];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count == -1) {
                throw badRequest("请求体不完整");
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
        try {
            JSONTokener tokener = new JSONTokener(new String(output.toByteArray(), StandardCharsets.UTF_8));
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
                throw badRequest("请求体必须为单个 JSON 对象");
            }
            return (JSONObject) value;
        } catch (JSONException e) {
            throw badRequest("JSON 格式错误");
        }
    }

    private static ApiException badRequest(String message) {
        return new ApiException(NanoHTTPD.Response.Status.BAD_REQUEST, message);
    }
}
