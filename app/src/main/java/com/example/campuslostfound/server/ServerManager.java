package com.example.campuslostfound.server;

import android.content.Context;

import com.example.campuslostfound.data.AppDbHelper;

import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;

/** 全进程只维护一个监听实例。调用方在后台线程执行启停。 */
public final class ServerManager {
    private static final ServerManager INSTANCE = new ServerManager();
    private LanServer server;

    private ServerManager() { }

    public static ServerManager getInstance() {
        return INSTANCE;
    }

    public synchronized void start(Context context) throws IOException {
        if (isRunning()) {
            return;
        }
        stop();
        AppDbHelper helper = AppDbHelper.getInstance(context);
        helper.initialize();
        LanServer candidate = new LanServer(HostIdentity.getOrCreate(context), new SessionStore(helper));
        try {
            candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            server = candidate;
        } catch (IOException | RuntimeException e) {
            candidate.stop();
            throw e;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public synchronized boolean isRunning() {
        return server != null && server.isAlive();
    }
}
