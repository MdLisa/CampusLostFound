package com.example.campuslostfound;

import static org.junit.Assert.*;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.widget.Spinner;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.campuslostfound.data.AppDbHelper;
import com.example.campuslostfound.data.HostAdminRepository;
import com.example.campuslostfound.data.PostDao;
import com.example.campuslostfound.data.ClaimDao;
import com.example.campuslostfound.server.ServerManager;
import com.example.campuslostfound.ui.HostActivity;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 仅在专用设备手动运行；插入并清理自己的测试记录，不清空业务表。 */
@RunWith(AndroidJUnit4.class)
public class HostAdminInstrumentedTest {
    private AppDbHelper helper;
    private HostAdminRepository admin;
    private final List<Long> members = new ArrayList<>();
    private final List<Long> posts = new ArrayList<>();

    @Before public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = AppDbHelper.getInstance(context);
        helper.initialize();
        admin = new HostAdminRepository(helper);
    }
    @After public void cleanUp() {
        helper.withWriteTransaction(db -> {
            db.execSQL("DROP TRIGGER IF EXISTS test_host_admin_fail");
            for (long id : posts) {
                String[] args = {Long.toString(id)};
                db.delete("admin_actions", "post_id=?", args);
                db.delete("claims", "post_id=?", args);
                db.delete("posts", "id=?", args);
            }
            for (long id : members) db.delete("members", "id=?", new String[]{Long.toString(id)});
            return null;
        });
    }

    @Test public void closingUpdatesOnlyPendingAndWritesOneLog() throws Exception {
        long owner = member(), applicant = member(), other = member();
        long post = post(owner, "管理关闭（测试）");
        long pending = claim(post, applicant), cancelled = claim(post, other);
        helper.withWriteTransaction(db -> {
            new ClaimDao().setStatus(db, cancelled, "CANCELLED", null, 1); return null;
        });
        admin.closePost(post, "  信息有误  ");
        helper.read(db -> {
            try {
                assertEquals("CLOSED", new PostDao().get(db, post).getString("status"));
                JSONObject first = new ClaimDao().get(db, pending);
                assertEquals("REJECTED", first.getString("status"));
                assertEquals("管理员关闭信息：信息有误", first.getString("note"));
                assertEquals("CANCELLED", new ClaimDao().get(db, cancelled).getString("status"));
                try (Cursor c = db.rawQuery("SELECT COUNT(*),reason FROM admin_actions WHERE post_id=?",
                        new String[]{Long.toString(post)})) {
                    assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)); assertEquals("信息有误", c.getString(1));
                }
            } catch (Exception e) { throw new AssertionError(e); }
            return null;
        });
        assertRejected(() -> admin.closePost(post, "重复关闭"));
    }

    @Test public void completedPostAndInvalidReasonsCannotBeClosed() throws Exception {
        long post = post(member(), "已经完成（测试）");
        for (String reason : new String[]{"", "a", new String(new char[101]).replace('\0', '字')})
            assertRejected(() -> admin.closePost(post, reason));
        helper.withWriteTransaction(db -> { new PostDao().setStatus(db, post, "RESOLVED", 1); return null; });
        assertRejected(() -> admin.closePost(post, "不应关闭"));
        assertEquals("RESOLVED", admin.getPost(post).getString("status"));
    }

    @Test public void logFailureRollsBackPostAndClaims() throws Exception {
        long post = post(member(), "事务回滚（测试）");
        long claim = claim(post, member());
        helper.withWriteTransaction(db -> {
            db.execSQL("CREATE TEMP TRIGGER test_host_admin_fail BEFORE INSERT ON admin_actions"
                    + " BEGIN SELECT RAISE(ABORT,'test rollback'); END"); return null;
        });
        try { admin.closePost(post, "回滚演示"); fail("expected log failure"); }
        catch (android.database.sqlite.SQLiteException expected) { }
        assertEquals("OPEN", admin.getPost(post).getString("status"));
        helper.read(db -> {
            try { assertEquals("PENDING", new ClaimDao().get(db, claim).getString("status")); }
            catch (Exception e) { throw new AssertionError(e); }
            return null;
        });
    }

    @Test public void localListsIncludeClosedAndEscapeSearchCharacters() throws Exception {
        long owner = member();
        long first = post(owner, "管理查找%_（测试）");
        post(owner, "管理查找AB（测试）");
        admin.closePost(first, "测试查询");
        JSONObject data = admin.listPosts(null, null, null, "%_", owner);
        assertEquals(1, data.getJSONArray("items").length());
        assertEquals(first, data.getJSONArray("items").getJSONObject(0).getLong("id"));
        assertEquals("CLOSED", data.getJSONArray("items").getJSONObject(0).getString("status"));
        long applicant = member();
        long second = post(owner, "申请隐私（测试）");
        claim(second, applicant);
        JSONObject summary = admin.memberClaims(applicant).getJSONArray("items").getJSONObject(0);
        assertFalse(summary.has("proof")); assertFalse(summary.has("contact"));
        JSONObject user = admin.listMembers(Long.toString(applicant)).getJSONArray("items").getJSONObject(0);
        assertFalse(user.has("token_hash")); assertEquals(applicant, user.getLong("id"));
    }

    @Test public void overviewCountsBeyondListLimit() throws Exception {
        long owner = member();
        long before = admin.overview().getLong("posts");
        for (int i = 0; i < 101; i++) post(owner, "数量限制（测试）" + i);
        assertEquals(before + 101, admin.overview().getLong("posts"));
        JSONObject list = admin.listPosts(null, null, null, null, owner);
        assertEquals(100, list.getJSONArray("items").length()); assertTrue(list.getBoolean("hasMore"));
    }

    @Test public void adminCloseRacingWithHandoverKeepsAConsistentOutcome() throws Exception {
        long owner = member();
        long post = post(owner, "竞争状态（测试）");
        long claim = claim(post, member());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        com.example.campuslostfound.server.BusinessRouter router =
                new com.example.campuslostfound.server.BusinessRouter(
                        new com.example.campuslostfound.server.DatabaseBusinessPort(helper,
                                new com.example.campuslostfound.server.ImageStore(context, helper)));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        try {
            java.util.concurrent.Future<Boolean> close = pool.submit(() -> {
                start.await();
                try { admin.closePost(post, "竞争关闭"); return true; }
                catch (IllegalArgumentException expected) { return false; }
            });
            java.util.concurrent.Future<Integer> handover = pool.submit(() -> {
                start.await();
                return router.route("POST", "/api/claims/" + claim + "/confirm",
                        java.util.Collections.emptyMap(), new JSONObject(), owner).status;
            });
            boolean closed = close.get(10, java.util.concurrent.TimeUnit.SECONDS);
            int status = handover.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(closed ? 409 : 200, status);
            helper.read(db -> {
                try {
                    assertEquals(closed ? "CLOSED" : "RESOLVED", new PostDao().get(db, post).getString("status"));
                    assertEquals(closed ? "REJECTED" : "ACCEPTED", new ClaimDao().get(db, claim).getString("status"));
                    try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM admin_actions WHERE post_id=?",
                            new String[]{Long.toString(post)})) {
                        assertTrue(c.moveToFirst()); assertEquals(closed ? 1 : 0, c.getInt(0));
                    }
                } catch (Exception e) { throw new AssertionError(e); }
                return null;
            });
        } finally { pool.shutdownNow(); }
    }


    @Test public void versionOneMigrationPreservesExistingRows() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            helper.onConfigure(db);
            createVersionOneSchema(db);
            db.execSQL("INSERT INTO members(id,nickname,token_hash,created_at) VALUES(41,'旧用户','old-hash',1)");
            db.execSQL("INSERT INTO posts(id,owner_id,type,title,description,category,location,status,created_at,updated_at)"
                    + " VALUES(42,41,'FOUND','旧帖子','保留旧记录','KEYS','图书馆','OPEN',1,1)");
            db.execSQL("INSERT INTO claims(id,post_id,applicant_id,proof,contact,status,created_at,updated_at)"
                    + " VALUES(43,42,41,'旧证明','旧联系方式','PENDING',1,1)");
            helper.onUpgrade(db, 1, AppDbHelper.DATABASE_VERSION);
            try (Cursor c = db.rawQuery("SELECT nickname,token_hash FROM members WHERE id=41", null)) {
                assertTrue(c.moveToFirst()); assertEquals("旧用户", c.getString(0)); assertEquals("old-hash", c.getString(1));
            }
            assertEquals("旧帖子", new PostDao().get(db, 42).getString("title"));
            assertEquals("PENDING", new ClaimDao().get(db, 43).getString("status"));
            db.execSQL("INSERT INTO admin_actions(post_id,post_title,reason,created_at) VALUES(42,'旧帖子','升级后可写',2)");
            try (Cursor c = db.rawQuery("PRAGMA foreign_key_check", null)) { assertFalse(c.moveToFirst()); }
        }
    }

    @Test public void switchingAdminSectionsKeepsHostReachableAndAdminHttpAbsent() throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            try (java.io.InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                            "pm grant " + context.getPackageName() + " android.permission.ACCESS_LOCAL_NETWORK"))) {
                while (stream.read() != -1) { }
            }
        }
        try (ActivityScenario<HostActivity> scenario = ActivityScenario.launch(HostActivity.class)) {
            scenario.onActivity(a -> a.findViewById(R.id.start_server).performClick());
            long end = android.os.SystemClock.uptimeMillis() + 10000;
            while (!ServerManager.getInstance().isRunning() && android.os.SystemClock.uptimeMillis() < end)
                android.os.SystemClock.sleep(50);
            assertTrue(ServerManager.getInstance().isRunning());
            for (int i = 0; i < 4; i++) {
                final int section = i;
                scenario.onActivity(a -> ((Spinner) a.findViewById(R.id.admin_section)).setSelection(section));
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertEquals(200, http("/api/ping"));
            }
            assertEquals(404, http("/api/admin/posts"));
        }
    }


    @Test public void deletedPostsDisappearFromUsersButClaimsAndAdminRecordsRemain() throws Exception {
        long owner = member(), applicant = member();
        long post = post(owner, "删除内容（测试）");
        long claim = claim(post, applicant);
        admin.deletePost(post, "测试软删除");
        helper.read(db -> {
            try {
                assertNull(new PostDao().get(db, post));
                assertEquals(0, new PostDao().list(db, owner, null, null, null, null).getJSONArray("items").length());
                JSONObject record = new ClaimDao().get(db, claim);
                assertEquals("REJECTED", record.getString("status"));
                assertTrue(record.getBoolean("postDeleted"));
                assertEquals("该信息已被管理员删除", record.getString("postTitle"));
                assertEquals("管理员删除信息：测试软删除", record.getString("note"));
            } catch (Exception e) { throw new AssertionError(e); }
            return null;
        });
        assertTrue(admin.getPost(post).getBoolean("deleted"));
        assertEquals("删除内容（测试）", admin.getPost(post).getString("title"));
        assertEquals(1, admin.listPosts(null, null, "DELETED", null, owner).getJSONArray("items").length());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        com.example.campuslostfound.server.BusinessRouter router = new com.example.campuslostfound.server.BusinessRouter(
                new com.example.campuslostfound.server.DatabaseBusinessPort(helper,
                        new com.example.campuslostfound.server.ImageStore(context, helper)));
        assertEquals(404, router.route("GET", "/api/posts/" + post, java.util.Collections.emptyMap(), null, 0).status);
        assertEquals(404, router.route("POST", "/api/posts/" + post + "/close",
                java.util.Collections.emptyMap(), new JSONObject(), owner).status);
    }

    @Test public void deletionRetainsCompletedHandoverAndRejectsDuplicateOrBadReason() throws Exception {
        long post = post(member(), "已交接后删除（测试）");
        long claim = claim(post, member());
        helper.withWriteTransaction(db -> {
            new PostDao().setStatus(db, post, "RESOLVED", 1);
            new ClaimDao().setStatus(db, claim, "ACCEPTED", "已确认完成交接", 1);
            return null;
        });
        assertRejected(() -> admin.deletePost(post, "a"));
        admin.deletePost(post, "保留交接历史");
        assertRejected(() -> admin.deletePost(post, "重复删除"));
        assertRejected(() -> admin.closePost(post, "删除后关闭"));
        helper.read(db -> {
            try {
                assertEquals("ACCEPTED", new ClaimDao().get(db, claim).getString("status"));
                assertEquals("RESOLVED", new PostDao().getForAdmin(db, post).getString("status"));
                try (Cursor c = db.rawQuery("SELECT action,COUNT(*) FROM admin_actions WHERE post_id=?",
                        new String[]{Long.toString(post)})) {
                    assertTrue(c.moveToFirst()); assertEquals("DELETE", c.getString(0)); assertEquals(1, c.getInt(1));
                }
            } catch (Exception e) { throw new AssertionError(e); }
            return null;
        });
    }

    @Test public void deleteLogFailureRollsBackVisibilityAndClaimState() throws Exception {
        long post = post(member(), "删除回滚（测试）");
        long claim = claim(post, member());
        helper.withWriteTransaction(db -> {
            db.execSQL("CREATE TEMP TRIGGER test_host_admin_fail BEFORE INSERT ON admin_actions"
                    + " BEGIN SELECT RAISE(ABORT,'test rollback'); END"); return null;
        });
        try { admin.deletePost(post, "测试删除回滚"); fail("expected log failure"); }
        catch (android.database.sqlite.SQLiteException expected) { }
        assertFalse(admin.getPost(post).getBoolean("deleted"));
        helper.read(db -> {
            try {
                assertNotNull(new PostDao().get(db, post));
                assertEquals("PENDING", new ClaimDao().get(db, claim).getString("status"));
            } catch (Exception e) { throw new AssertionError(e); }
            return null;
        });
    }

    @Test public void deletedPhotoIsBlockedForHttpButRetainedForLocalAdmin() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        com.example.campuslostfound.server.ImageStore images =
                new com.example.campuslostfound.server.ImageStore(context, helper);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bytes);
        bitmap.recycle();
        String name = images.saveJpeg(android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP));
        try {
            long post = post(member(), "带图软删除（测试）");
            helper.withWriteTransaction(db -> {
                ContentValues values = new ContentValues(); values.put("image_name", name);
                db.update("posts", values, "id=?", new String[]{Long.toString(post)}); return null;
            });
            try (java.io.InputStream input = images.openPublishedJpeg(name)) { assertTrue(input.read() >= 0); }
            admin.deletePost(post, "图片访问测试");
            try (java.io.InputStream ignored = images.openPublishedJpeg(name)) { fail("deleted image must be hidden"); }
            catch (com.example.campuslostfound.common.ApiException expected) {
                assertEquals(fi.iki.elonen.NanoHTTPD.Response.Status.NOT_FOUND, expected.status);
            }
            try (java.io.InputStream input = images.openAdminJpeg(name)) { assertTrue(input.read() >= 0); }
        } finally { images.deleteJpeg(name); }
    }

    @Test public void versionTwoMigrationPreservesCloseLogsAndDefaultsVisibility() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            helper.onConfigure(db);
            createVersionOneSchema(db);
            db.execSQL("CREATE TABLE admin_actions (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "post_id INTEGER NOT NULL REFERENCES posts(id),post_title TEXT NOT NULL,"
                    + "reason TEXT NOT NULL,created_at INTEGER NOT NULL)");
            db.execSQL("INSERT INTO members VALUES(41,'旧用户','old-hash',1)");
            db.execSQL("INSERT INTO posts(id,owner_id,type,title,description,category,location,status,created_at,updated_at)"
                    + " VALUES(42,41,'FOUND','旧关闭帖','保留内容','KEYS','图书馆','CLOSED',1,1)");
            db.execSQL("INSERT INTO admin_actions(post_id,post_title,reason,created_at) VALUES(42,'旧关闭帖','历史原因',1)");
            helper.onUpgrade(db, 2, AppDbHelper.DATABASE_VERSION);
            assertFalse(new PostDao().getForAdmin(db, 42).getBoolean("deleted"));
            try (Cursor c = db.rawQuery("SELECT action,reason FROM admin_actions", null)) {
                assertTrue(c.moveToFirst()); assertEquals("CLOSE", c.getString(0)); assertEquals("历史原因", c.getString(1));
            }
            try (Cursor c = db.rawQuery("PRAGMA foreign_key_check", null)) { assertFalse(c.moveToFirst()); }
        }
    }

    private static void createVersionOneSchema(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE members ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "nickname TEXT NOT NULL,"
                + "token_hash TEXT NOT NULL UNIQUE,"
                + "created_at INTEGER NOT NULL)");
        database.execSQL("CREATE TABLE posts ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "owner_id INTEGER NOT NULL REFERENCES members(id),"
                + "type TEXT NOT NULL CHECK(type IN ('LOST','FOUND')),"
                + "title TEXT NOT NULL,description TEXT NOT NULL,"
                + "category TEXT NOT NULL CHECK(category IN "
                + "('CARD','ELECTRONICS','KEYS','BOOKS','DAILY','OTHER')),"
                + "location TEXT NOT NULL,image_name TEXT,"
                + "status TEXT NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED','CLOSED')),"
                + "created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        database.execSQL("CREATE TABLE claims ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "post_id INTEGER NOT NULL REFERENCES posts(id),"
                + "applicant_id INTEGER NOT NULL REFERENCES members(id),"
                + "proof TEXT NOT NULL,contact TEXT NOT NULL,"
                + "status TEXT NOT NULL DEFAULT 'PENDING' "
                + "CHECK(status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED')),"
                + "note TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,"
                + "UNIQUE(post_id,applicant_id))");
        database.execSQL("CREATE INDEX idx_posts_owner ON posts(owner_id)");
        database.execSQL("CREATE INDEX idx_posts_status_created ON posts(status,created_at,id)");
        database.execSQL("CREATE INDEX idx_claims_post_status ON claims(post_id,status)");
        database.execSQL("CREATE INDEX idx_claims_applicant_created ON claims(applicant_id,created_at,id)");
    }

    private int http(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:8080" + path).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        try { return connection.getResponseCode(); } finally { connection.disconnect(); }
    }
    private long member() {
        long id = helper.withWriteTransaction(db -> {
            ContentValues values = new ContentValues();
            values.put("nickname", "管理测试" + UUID.randomUUID());
            values.put("token_hash", UUID.randomUUID().toString()); values.put("created_at", System.currentTimeMillis());
            return db.insertOrThrow("members", null, values);
        });
        members.add(id); return id;
    }
    private long post(long owner, String title) throws Exception {
        JSONObject input = new JSONObject().put("type", "FOUND").put("title", title)
                .put("description", "管理端自动测试信息").put("category", "KEYS").put("location", "图书馆");
        long id = helper.withWriteTransaction(db -> new PostDao().insert(db, owner, input, null, System.currentTimeMillis()));
        posts.add(id); return id;
    }
    private long claim(long post, long applicant) {
        return helper.withWriteTransaction(db -> new ClaimDao().insert(db, post, applicant, "测试物品特征证明",
                "测试联系方式", System.currentTimeMillis()));
    }
    private void assertRejected(Runnable work) {
        try { work.run(); fail("expected rejection"); } catch (IllegalArgumentException expected) { }
    }
}
