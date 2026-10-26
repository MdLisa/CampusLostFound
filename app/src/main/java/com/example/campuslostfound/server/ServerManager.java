package com.example.campuslostfound.server;

import android.content.Context;

import com.example.campuslostfound.data.AppDbHelper;

import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;

/** 全进程只维护一个监听实例。调用方在后台线程执行启停。 */
public final class ServerManager {
    private static final ServerManager INSTANCE = new ServerManager();
    private volatile LanServer server;

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
        ImageStore images = new ImageStore(context, helper);
        BusinessRouter business = new BusinessRouter(new DatabaseBusinessPort(helper, images));
        LanServer candidate = new LanServer(
                HostIdentity.getOrCreate(context), new SessionStore(helper), business, images);
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

    /** 界面只读取快照，不等待后台启动、数据库初始化或停止所持有的锁。 */
    public boolean isRunning() {
        LanServer current = server;
        return current != null && current.isAlive();
    }
}
