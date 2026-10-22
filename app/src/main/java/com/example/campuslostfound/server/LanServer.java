package com.example.campuslostfound.server;

import com.example.campuslostfound.common.ApiConstants;
import com.example.campuslostfound.common.ApiException;
import com.example.campuslostfound.common.JsonResponse;
import com.example.campuslostfound.data.Member;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;

import fi.iki.elonen.NanoHTTPD;

/** A 的基础路由；后续将业务路由交给 B 的 Handler。 */
public final class LanServer extends NanoHTTPD {
    private final String hostId;
    private final SessionStore sessions;

    public LanServer(String hostId, SessionStore sessions) {
        super(ApiConstants.SERVER_PORT);
        this.hostId = hostId;
        this.sessions = sessions;
    }

    @Override
    public Response serve(IHTTPSession session) {
        Response response;
        try {
            response = route(session);
        } catch (ApiException e) {
            response = JsonResponse.error(e.status, e.getMessage());
        } catch (SocketTimeoutException e) {
            response = JsonResponse.error(Response.Status.BAD_REQUEST, "读取请求体超时");
        } catch (IOException e) {
            response = JsonResponse.error(Response.Status.BAD_REQUEST, "无法读取完整请求体");
        } catch (JSONException | RuntimeException e) {
            // 不把堆栈、token 或设备路径回传给客户端。
            response = JsonResponse.error(Response.Status.INTERNAL_ERROR, "主机内部错误");
        }
        // 当前按一次连接处理一个请求，避免未读取的非法请求体被当作后续请求。
        response.closeConnection(true);
        return response;
    }

    private Response route(IHTTPSession session) throws JSONException, IOException {
        String path = session.getUri();
        if (Method.GET.equals(session.getMethod()) && "/api/ping".equals(path)) {
            return JsonResponse.success(new JSONObject()
                    .put("app", ApiConstants.APP_ID)
                    .put("protocolVersion", ApiConstants.PROTOCOL_VERSION)
                    .put("hostId", hostId));
        }
        if (Method.POST.equals(session.getMethod()) && "/api/sessions".equals(path)) {
            return JsonResponse.success(sessions.createSession(JsonRequest.readObject(session), hostId));
        }
        if (Method.GET.equals(session.getMethod()) && "/api/session".equals(path)) {
            Member member = sessions.requireMemberFromHeader(session.getHeaders().get("authorization"));
            return JsonResponse.success(new JSONObject().put("member", member.toJson()).put("hostId", hostId));
        }
        return JsonResponse.error(Response.Status.NOT_FOUND, "接口不存在");
    }
}
