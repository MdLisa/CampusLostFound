package com.example.campuslostfound.server;

import com.example.campuslostfound.common.ApiConstants;
import com.example.campuslostfound.common.JsonResponse;

import org.json.JSONException;
import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;

/** 第一步只提供 ping，后续在此分发 B 的业务 Handler。 */
public final class LanServer extends NanoHTTPD {
    private final String hostId;

    public LanServer(String hostId) {
        // 监听所有本地网卡，使另一台手机可通过局域网 IP 访问。
        super(ApiConstants.SERVER_PORT);
        this.hostId = hostId;
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (Method.GET.equals(session.getMethod()) && "/api/ping".equals(session.getUri())) {
            try {
                JSONObject data = new JSONObject();
                data.put("app", ApiConstants.APP_ID);
                data.put("protocolVersion", ApiConstants.PROTOCOL_VERSION);
                data.put("hostId", hostId);
                return JsonResponse.success(data);
            } catch (JSONException e) {
                return JsonResponse.error(Response.Status.INTERNAL_ERROR, "主机内部错误");
            }
        }
        return JsonResponse.error(Response.Status.NOT_FOUND, "接口不存在");
    }
}

