package com.example.campuslostfound.common;

import fi.iki.elonen.NanoHTTPD;

/** 可直接向客户端展示的接口错误；不要放入 token、堆栈或磁盘路径。 */
public final class ApiException extends RuntimeException {
    public final NanoHTTPD.Response.Status status;

    public ApiException(NanoHTTPD.Response.Status status, String message) {
        super(message);
        this.status = status;
    }
}
