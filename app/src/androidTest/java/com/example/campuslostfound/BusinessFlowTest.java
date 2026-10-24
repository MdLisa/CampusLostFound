package com.example.campuslostfound;

import static org.junit.Assert.*;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.campuslostfound.server.*;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs on an emulator/device. In-memory schema follows the shared contract. */
@RunWith(AndroidJUnit4.class)
public final class BusinessFlowTest {
    private SQLiteDatabase db;
    private BusinessRouter router;
    @Before public void setUp() {
        db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE members(id INTEGER PRIMARY KEY,nickname TEXT NOT NULL)");
        db.execSQL("CREATE TABLE posts(id INTEGER PRIMARY KEY AUTOINCREMENT,owner_id INTEGER NOT NULL,type TEXT NOT NULL,title TEXT NOT NULL,description TEXT NOT NULL,category TEXT NOT NULL,location TEXT NOT NULL,image_name TEXT,status TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE claims(id INTEGER PRIMARY KEY AUTOINCREMENT,post_id INTEGER NOT NULL,applicant_id INTEGER NOT NULL,proof TEXT NOT NULL,contact TEXT NOT NULL,status TEXT NOT NULL,note TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,UNIQUE(post_id,applicant_id))");
        db.execSQL("INSERT INTO members VALUES(1,'发布者'),(2,'申请人甲'),(3,'申请人乙')");
        BusinessPort port = new BusinessPort() {
            final Object lock = new Object();
            public SQLiteDatabase readableDatabase() { return db; }
            public <T> T read(Work<T> work) throws Exception { synchronized (lock) { return work.run(db); } }
            public <T> T writeTransaction(Work<T> work) throws Exception {
                synchronized (lock) {
                    db.beginTransaction();
                    try { T result = work.run(db); db.setTransactionSuccessful(); return result; }
                    finally { db.endTransaction(); }
                }
            }
            public String saveJpeg(String value) { throw new AssertionError("no image in test"); }
            public void deleteJpeg(String name) { }
        };
        router = new BusinessRouter(port);
    }
    @After public void tearDown() { db.close(); }

    @Test public void confirmRejectsOtherAndHidesPrivateFields() throws Exception {
        long postId = publish();
        long first = claim(postId, 2);
        long second = claim(postId, 3);
        BusinessRouter.Reply publicPost = call("GET", "/api/posts/" + postId, 0, new JSONObject());
        assertEquals(200, publicPost.status);
        assertFalse(publicPost.json.toString().contains("微信"));
        assertEquals(403, call("GET", "/api/posts/" + postId + "/claims", 3, new JSONObject()).status);
        assertEquals(200, call("POST", "/api/claims/" + first + "/confirm", 1, new JSONObject()).status);
        assertEquals(200, call("POST", "/api/claims/" + first + "/confirm", 1, new JSONObject()).status);
        assertEquals(409, call("POST", "/api/claims/" + second + "/confirm", 1, new JSONObject()).status);
        JSONObject received = call("GET", "/api/posts/" + postId + "/claims", 1, new JSONObject()).json.getJSONObject("data");
        assertEquals("RESOLVED", call("GET", "/api/posts/" + postId, 0, new JSONObject()).json.getJSONObject("data").getString("status"));
        assertEquals(2, received.getJSONArray("items").length());
        assertEquals("REJECTED", received.getJSONArray("items").getJSONObject(0).getString("status"));
    }

    @Test public void duplicateAndCloseCannotLeavePending() throws Exception {
        long postId = publish();
        claim(postId, 2);
        assertEquals(409, call("POST", "/api/posts/" + postId + "/claims", 2, claimBody()).status);
        assertEquals(200, call("POST", "/api/posts/" + postId + "/close", 1, new JSONObject()).status);
        assertEquals(409, call("POST", "/api/posts/" + postId + "/claims", 3, claimBody()).status);
        JSONObject mine = call("GET", "/api/me/claims", 2, new JSONObject()).json.getJSONObject("data");
        assertEquals("REJECTED", mine.getJSONArray("items").getJSONObject(0).getString("status"));
    }

    @Test public void concurrentConfirmOnlyAcceptsOne() throws Exception {
        long postId = publish();
        long first = claim(postId, 2), second = claim(postId, 3);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Future<Integer> a = pool.submit(() -> { start.await(); return call("POST", "/api/claims/" + first + "/confirm", 1, new JSONObject()).status; });
            java.util.concurrent.Future<Integer> b = pool.submit(() -> { start.await(); return call("POST", "/api/claims/" + second + "/confirm", 1, new JSONObject()).status; });
            start.countDown();
            assertEquals(609, a.get() + b.get()); // exactly 200 + 409
            JSONObject received = call("GET", "/api/posts/" + postId + "/claims", 1, new JSONObject()).json.getJSONObject("data");
            int accepted = 0;
            for (int i = 0; i < 2; i++) if ("ACCEPTED".equals(received.getJSONArray("items").getJSONObject(i).getString("status"))) accepted++;
            assertEquals(1, accepted);
        } finally { pool.shutdownNow(); }
    }

    @Test public void concurrentSubmitAndCloseCannotLeavePending() throws Exception {
        long postId = publish();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Future<Integer> submit = pool.submit(() -> { start.await(); return call("POST", "/api/posts/" + postId + "/claims", 2, claimBody()).status; });
            java.util.concurrent.Future<Integer> close = pool.submit(() -> { start.await(); return call("POST", "/api/posts/" + postId + "/close", 1, new JSONObject()).status; });
            start.countDown();
            int submitStatus = submit.get();
            assertTrue(submitStatus == 200 || submitStatus == 409);
            assertEquals(200, (int) close.get());
            JSONObject received = call("GET", "/api/posts/" + postId + "/claims", 1, new JSONObject()).json.getJSONObject("data");
            for (int i = 0; i < received.getJSONArray("items").length(); i++)
                assertFalse("PENDING".equals(received.getJSONArray("items").getJSONObject(i).getString("status")));
        } finally { pool.shutdownNow(); }
    }

    private long publish() throws Exception {
        JSONObject body = new JSONObject().put("type", "FOUND").put("title", "钥匙招领").put("description", "图书馆二楼捡到钥匙").put("category", "KEYS").put("location", "图书馆二楼");
        return call("POST", "/api/posts", 1, body).json.getJSONObject("data").getLong("id");
    }
    private long claim(long postId, long userId) throws Exception {
        return call("POST", "/api/posts/" + postId + "/claims", userId, claimBody()).json.getJSONObject("data").getLong("id");
    }
    private JSONObject claimBody() throws Exception { return new JSONObject().put("proof", "钥匙有三把和蓝色挂件").put("contact", "微信 campus_test"); }
    private BusinessRouter.Reply call(String method, String path, long userId, JSONObject body) {
        return router.route(method, path, java.util.Collections.emptyMap(), body, userId);
    }
}
