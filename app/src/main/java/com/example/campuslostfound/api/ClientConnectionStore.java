package com.example.campuslostfound.api;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** 地址、主机和本机身份统一保存在私有配置，不建立客户端业务数据库。 */
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

    public synchronized boolean hasIdentity() {
        return !preferences.getString("token", "").isEmpty()
                && preferences.getLong("member_id", 0) > 0;
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

    /** 仅在后台请求线程调用；保存成功才通知界面，原 token 不对界面暴露。 */
    synchronized Snapshot saveIdentity(Snapshot selected, JSONObject data, boolean created)
            throws JSONException, IOException {
        if (!isCurrent(selected) || !verified) {
            throw new IOException("连接已改变");
        }
        String hostId = data.getString("hostId");
        if (!hostId.equals(preferences.getString("host_id", ""))) {
            removeIdentity(preferences.edit()).apply();
            verified = false;
            revision++;
            throw new JSONException("身份响应的主机标识不匹配");
        }
        JSONObject member = data.getJSONObject("member");
        Object rawId = member.get("id");
        Object rawNickname = member.get("nickname");
        if (!(rawId instanceof Integer || rawId instanceof Long) || ((Number) rawId).longValue() <= 0
                || !(rawNickname instanceof String) || ((String) rawNickname).trim().isEmpty()) {
            throw new JSONException("无效用户信息");
        }
        String token = created ? data.getString("token") : selected.token;
        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            throw new JSONException("无效身份凭据");
        }
        boolean saved = preferences.edit().putString("host_id", hostId).putString("token", token)
                .putLong("member_id", ((Number) rawId).longValue())
                .putString("nickname", (String) rawNickname).commit();
        if (!saved) {
            throw new IOException("无法保存本机身份");
        }
        revision++;
        return snapshot();
    }

    /** 只清除当前请求所对应的身份，旧请求不能清掉新主机或新用户。 */
    synchronized Snapshot clearIdentityIfCurrent(Snapshot selected) {
        if (!isCurrent(selected)) {
            return selected;
        }
        removeIdentity(preferences.edit()).apply();
        revision++;
        return snapshot();
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(getBaseAddress(), preferences.getString("token", ""), revision, verified);
    }

    synchronized boolean isCurrent(Snapshot selected) {
        return selected.revision == revision && selected.address.equals(getBaseAddress());
    }

    private SharedPreferences.Editor removeIdentity(SharedPreferences.Editor editor) {
        return editor.remove("token").remove("member_id").remove("nickname");
    }

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
