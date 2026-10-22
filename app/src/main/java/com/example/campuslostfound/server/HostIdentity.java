package com.example.campuslostfound.server;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.UUID;

/** 主机标识独立于 IP；普通重启和停止服务均不会改变它。 */
public final class HostIdentity {
    private HostIdentity() { }

    public static synchronized String getOrCreate(Context context) throws IOException {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences("host_config", Context.MODE_PRIVATE);
        String hostId = preferences.getString("host_id", null);
        if (hostId == null) {
            hostId = UUID.randomUUID().toString();
            // 在后台线程同步写入，确保对外响应前已持久保存。
            if (!preferences.edit().putString("host_id", hostId).commit()) {
                throw new IOException("无法保存主机标识，请检查手机存储空间");
            }
        }
        return hostId;
    }
}

