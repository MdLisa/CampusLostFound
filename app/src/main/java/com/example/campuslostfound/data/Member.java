package com.example.campuslostfound.data;

import org.json.JSONException;
import org.json.JSONObject;

/** 对外只暴露 ID 和昵称。 */
public final class Member {
    public final long id;
    public final String nickname;

    public Member(long id, String nickname) {
        this.id = id;
        this.nickname = nickname;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("nickname", nickname);
    }
}
