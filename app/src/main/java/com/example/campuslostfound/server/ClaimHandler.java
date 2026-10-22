package com.example.campuslostfound.server;

import com.example.campuslostfound.data.ClaimDao;
import com.example.campuslostfound.data.PostDao;
import org.json.JSONObject;

/** B05-B10. A must run writeTransaction under its shared DB_LOCK. */
public final class ClaimHandler {
    private final BusinessPort port;
    private final PostDao posts = new PostDao();
    private final ClaimDao claims = new ClaimDao();
    public ClaimHandler(BusinessPort port) { this.port = port; }

    public JSONObject submit(long memberId, long postId, JSONObject body) throws Exception {
        PostHandler.requireMember(memberId);
        String proof = PostHandler.field(body, "proof", 10, 300);
        String contact = PostHandler.field(body, "contact", 3, 80);
        return port.writeTransaction(db -> {
            JSONObject post = posts.get(db, postId);
            if (post == null) throw new BusinessException(404, "帖子不存在");
            if (post.getJSONObject("owner").getLong("id") == memberId) throw new BusinessException(403, "不能申请自己的帖子");
            if (!"OPEN".equals(post.getString("status"))) throw new BusinessException(409, "帖子已结束");
            if (claims.exists(db, postId, memberId)) throw new BusinessException(409, "你已经提交过申请");
            long id = claims.insert(db, postId, memberId, proof, contact, System.currentTimeMillis());
            return claims.get(db, id);
        });
    }

    public JSONObject mine(long memberId, String status) throws Exception {
        PostHandler.requireMember(memberId); status(status);
        return port.read(db -> claims.list(db, memberId, null, status));
    }

    public JSONObject received(long memberId, long postId, String status) throws Exception {
        PostHandler.requireMember(memberId); status(status);
        return port.read(db -> {
            JSONObject post = posts.get(db, postId);
            if (post == null) throw new BusinessException(404, "帖子不存在");
            if (post.getJSONObject("owner").getLong("id") != memberId) throw new BusinessException(403, "无权查看申请");
            return claims.list(db, null, postId, status);
        });
    }

    public JSONObject confirm(long memberId, long claimId) throws Exception {
        PostHandler.requireMember(memberId);
        return port.writeTransaction(db -> {
            JSONObject claim = required(db, claimId);
            long postId = claim.getLong("postId");
            JSONObject post = posts.get(db, postId);
            owner(memberId, post);
            if ("ACCEPTED".equals(claim.getString("status")) && "RESOLVED".equals(post.getString("status"))) return claim;
            pending(claim, post);
            long now = System.currentTimeMillis();
            claims.setStatus(db, claimId, "ACCEPTED", "已确认完成交接", now);
            posts.setStatus(db, postId, "RESOLVED", now);
            claims.rejectPending(db, postId, claimId, "物品已完成交接", now);
            return claims.get(db, claimId);
        });
    }

    public JSONObject reject(long memberId, long claimId, JSONObject body) throws Exception {
        PostHandler.requireMember(memberId);
        String reason = PostHandler.field(body, "reason", 2, 100);
        return port.writeTransaction(db -> {
            JSONObject claim = required(db, claimId);
            owner(memberId, posts.get(db, claim.getLong("postId")));
            pending(claim, posts.get(db, claim.getLong("postId")));
            claims.setStatus(db, claimId, "REJECTED", reason, System.currentTimeMillis());
            return claims.get(db, claimId);
        });
    }

    public JSONObject cancel(long memberId, long claimId) throws Exception {
        PostHandler.requireMember(memberId);
        return port.writeTransaction(db -> {
            JSONObject claim = required(db, claimId);
            if (claim.getJSONObject("applicant").getLong("id") != memberId) throw new BusinessException(403, "无权取消申请");
            pending(claim, posts.get(db, claim.getLong("postId")));
            claims.setStatus(db, claimId, "CANCELLED", null, System.currentTimeMillis());
            return claims.get(db, claimId);
        });
    }

    private JSONObject required(android.database.sqlite.SQLiteDatabase db, long claimId) throws Exception {
        JSONObject claim = claims.get(db, claimId);
        if (claim == null) throw new BusinessException(404, "申请不存在");
        return claim;
    }
    private static void owner(long memberId, JSONObject post) throws Exception {
        if (post == null) throw new BusinessException(404, "帖子不存在");
        if (post.getJSONObject("owner").getLong("id") != memberId) throw new BusinessException(403, "无权操作申请");
    }
    private static void pending(JSONObject claim, JSONObject post) throws Exception {
        if (post == null || !"OPEN".equals(post.getString("status")) || !"PENDING".equals(claim.getString("status")))
            throw new BusinessException(409, "当前状态不允许操作");
    }
    private static void status(String status) throws BusinessException {
        PostHandler.optional(status, "PENDING", "ACCEPTED", "REJECTED", "CANCELLED");
    }
}
