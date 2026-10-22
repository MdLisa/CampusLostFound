package com.example.campuslostfound.server;

import android.content.ContentValues;
import android.database.Cursor;
import android.util.Base64;

import com.example.campuslostfound.common.ApiException;
import com.example.campuslostfound.data.AppDbHelper;
import com.example.campuslostfound.data.Member;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Iterator;

import fi.iki.elonen.NanoHTTPD;

/** 身份只来自安全随机 token；昵称可以重复，不按昵称恢复身份。 */
public final class SessionStore {
    private final AppDbHelper helper;
    private final SecureRandom random = new SecureRandom();

    public SessionStore(AppDbHelper helper) {
        this.helper = helper;
    }

    public JSONObject createSession(JSONObject body, String hostId) throws JSONException {
        Iterator<String> keys = body.keys();
        while (keys.hasNext()) {
            if (!"nickname".equals(keys.next())) {
                throw new ApiException(NanoHTTPD.Response.Status.BAD_REQUEST, "请求只接受 nickname 字段");
            }
        }
        Object raw = body.opt("nickname");
        if (!(raw instanceof String)) {
            throw new ApiException(NanoHTTPD.Response.Status.BAD_REQUEST, "请填写昵称");
        }
        String nickname = ((String) raw).trim();
        int count = nickname.codePointCount(0, nickname.length());
        if (count < 1 || count > 20) {
            throw new ApiException(NanoHTTPD.Response.Status.BAD_REQUEST, "昵称去除首尾空白后须为 1～20 个字符");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String hash = sha256(token);
        Member member = helper.withWriteTransaction(database -> {
            ContentValues values = new ContentValues();
            values.put("nickname", nickname);
            values.put("token_hash", hash);
            values.put("created_at", System.currentTimeMillis());
            long id = database.insertOrThrow("members", null, values);
            return new Member(id, nickname);
        });
        return new JSONObject().put("member", member.toJson()).put("token", token).put("hostId", hostId);
    }

    public Member requireMember(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw unauthorized();
        }
        String hash = sha256(token);
        return helper.read(database -> {
            try (Cursor cursor = database.query("members", new String[]{"id", "nickname"},
                    "token_hash = ?", new String[]{hash}, null, null, null)) {
                if (!cursor.moveToFirst()) {
                    throw unauthorized();
                }
                return new Member(cursor.getLong(0), cursor.getString(1));
            }
        });
    }

    public Member requireMemberFromHeader(String authorization) {
        if (authorization == null) {
            throw unauthorized();
        }
        String[] parts = authorization.trim().split("\\s+", 2);
        if (parts.length != 2 || !"Bearer".equalsIgnoreCase(parts[0])) {
            throw unauthorized();
        }
        return requireMember(parts[1]);
    }

    private ApiException unauthorized() {
        return new ApiException(NanoHTTPD.Response.Status.UNAUTHORIZED, "身份无效，请重新填写昵称");
    }

    private String sha256(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            char[] digits = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder(64);
            for (byte value : hash) {
                result.append(digits[(value & 0xff) >>> 4]).append(digits[value & 0x0f]);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
