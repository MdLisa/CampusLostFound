package com.example.campuslostfound.server;

import com.example.campuslostfound.common.ApiConstants;
import com.example.campuslostfound.common.ApiException;
import com.example.campuslostfound.common.JsonResponse;
import com.example.campuslostfound.data.Member;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.FileInputStream;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** A 的基础路由；后续将业务路由交给 B 的 Handler。 */
public final class LanServer extends NanoHTTPD {
    private final String hostId;
    private final SessionStore sessions;
    private final BusinessRouter business;
    private final ImageStore images;

    public LanServer(String hostId, SessionStore sessions, BusinessRouter business, ImageStore images) {
        super(ApiConstants.SERVER_PORT);
        this.hostId = hostId;
        this.sessions = sessions;
        this.business = business;
        this.images = images;
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
        if (Method.GET.equals(session.getMethod()) && path.startsWith("/images/")) {
            FileInputStream input = null;
            try {
                input = images.openPublishedJpeg(path.substring("/images/".length()));
                Response response = newFixedLengthResponse(
                        Response.Status.OK, "image/jpeg", input, input.getChannel().size());
                response.addHeader("Cache-Control", "no-store");
                return response;
            } catch (IOException e) {
                if (input != null) {
                    try { input.close(); } catch (IOException ignored) { }
                }
                throw new ApiException(Response.Status.INTERNAL_ERROR, "图片读取失败");
            }
        }
        if (BusinessRouter.isBusinessPath(path)) {
            String method = session.getMethod().name();
            long memberId = 0;
            boolean needsMember = BusinessRouter.requiresMember(method, path);
            if (needsMember) {
                memberId = sessions.requireMemberFromHeader(
                        session.getHeaders().get("authorization")).id;
            }
            JSONObject body = needsMember && Method.POST.equals(session.getMethod())
                    ? JsonRequest.readObject(session) : null;
            if (body != null) {
                for (String key : new String[]{"userId", "memberId", "ownerId", "applicantId"}) {
                    if (body.has(key)) {
                        throw new ApiException(Response.Status.BAD_REQUEST, "请求不能指定操作者身份");
                    }
                }
            }
            Map<String, String> query = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : session.getParameters().entrySet()) {
                if (entry.getValue().size() != 1) {
                    throw new ApiException(Response.Status.BAD_REQUEST, "查询参数不能重复");
                }
                query.put(entry.getKey(), entry.getValue().get(0));
            }
            BusinessRouter.Reply reply = business.route(method, path, query, body, memberId);
            if (reply != null) {
                Response.Status status = Response.Status.lookup(reply.status);
                if (status == null) {
                    return JsonResponse.error(Response.Status.INTERNAL_ERROR, "主机内部错误");
                }
                Response response = newFixedLengthResponse(
                        status, "application/json; charset=utf-8", reply.json.toString());
                response.addHeader("Cache-Control", "no-store");
                return response;
            }
        }
        return JsonResponse.error(Response.Status.NOT_FOUND, "接口不存在");
    }
}
