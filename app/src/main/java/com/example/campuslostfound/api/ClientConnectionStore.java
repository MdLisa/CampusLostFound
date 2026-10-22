package com.example.campuslostfound.api;

import android.content.Context;
import android.content.SharedPreferences;

/** 客户端连接配置。只保存地址、主机及身份，不建立客户端业务数据库。 */
public final class ClientConnectionStore {
    private static ClientConnectionStore instance;
    private final SharedPreferences preferences;
    private long revision;
    private boolean verified;

    private ClientConnectionStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences("client_connection", Context.MODE_PRIVATE);
    }

    public static synchronized ClientConnectionStore getInstance(Context context) {
        if (instance == null) {
            instance = new ClientConnectionStore(context);
        }
        return instance;
    }

    public synchronized String getBaseAddress() {
        return preferences.getString("base_address", "");
    }

    synchronized Snapshot selectAddress(String address) {
        SharedPreferences.Editor editor = preferences.edit();
        if (!address.equals(getBaseAddress())) {
            removeIdentity(editor).remove("host_id");
        }
        editor.putString("base_address", address).apply();
        verified = false;
        revision++;
        return snapshot();
    }

    synchronized boolean confirmHost(Snapshot selected, String hostId) {
        if (!isCurrent(selected)) {
            return false;
        }
        SharedPreferences.Editor editor = preferences.edit();
        if (!hostId.equals(preferences.getString("host_id", ""))) {
            removeIdentity(editor);
        }
        editor.putString("host_id", hostId).apply();
        verified = true;
        return true;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(getBaseAddress(), preferences.getString("token", ""),
                revision, verified);
    }

    synchronized boolean isCurrent(Snapshot selected) {
        return selected.revision == revision && selected.address.equals(getBaseAddress());
    }

    private SharedPreferences.Editor removeIdentity(SharedPreferences.Editor editor) {
        return editor.remove("token").remove("member_id").remove("nickname");
    }

    // 第三步创建身份时沿用本配置文件与这些键；不在第二步虚构登录状态。
    static final class Snapshot {
        final String address;
        final String token;
        final long revision;
        final boolean verified;

        Snapshot(String address, String token, long revision, boolean verified) {
            this.address = address;
            this.token = token;
            this.revision = revision;
            this.verified = verified;
        }
    }
}
