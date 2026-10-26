package com.example.campuslostfound;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.util.Base64;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.campuslostfound.common.DatabaseLock;
import com.example.campuslostfound.data.AppDbHelper;
import com.example.campuslostfound.server.ImageStore;
import com.example.campuslostfound.server.ServerManager;
import com.example.campuslostfound.ui.HostActivity;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 只在专用测试设备手动运行；真实 HTTP、公共数据库与图片工具的回归。 */
@RunWith(AndroidJUnit4.class)
public class BackendRecoveryInstrumentedTest {
    private final ServerManager manager = ServerManager.getInstance();
    private final List<Long> memberIds = new ArrayList<>();
    private Context context;
    private long postId;
    private String imageName;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            android.os.ParcelFileDescriptor result = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().executeShellCommand("pm grant " + context.getPackageName()
                            + " android.permission.ACCESS_LOCAL_NETWORK");
            try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(result)) {
                while (input.read() != -1) { }
            }
        }
        manager.stop();
    }

    @After
    public void tearDown() throws Exception {
        manager.stop();
        // 只清理本用例创建的记录，不清空已有开发数据和身份配置。
        AppDbHelper helper = AppDbHelper.getInstance(context);
        helper.withWriteTransaction(db -> {
            for (long memberId : memberIds) {
                String[] args = {Long.toString(memberId)};
                db.delete("claims", "post_id IN (SELECT id FROM posts WHERE owner_id=?)", args);
                db.delete("posts", "owner_id=?", args);
                db.delete("members", "id=?", args);
            }
            return null;
        });
        if (imageName != null) new ImageStore(context, helper).deleteJpeg(imageName);
    }

    @Test
    public void runningSnapshotDoesNotWaitForLifecycleMonitor() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        try {
            synchronized (manager) {
                Future<Boolean> snapshot = worker.submit(() -> {
                    entered.countDown();
                    return manager.isRunning();
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFalse(snapshot.get(2, TimeUnit.SECONDS));
            }
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void recreatedHostPageStopsAndCanStartAgain() throws Exception {
        try (ActivityScenario<HostActivity> scenario = ActivityScenario.launch(HostActivity.class)) {
            scenario.onActivity(activity -> activity.findViewById(R.id.start_server).performClick());
            awaitRunning(true);
            scenario.recreate();
            awaitRunning(false);
            awaitStartEnabled(scenario);
            scenario.onActivity(activity -> activity.findViewById(R.id.start_server).performClick());
            awaitRunning(true);
            request("GET", "/api/ping", null, null, 200);
        }
        awaitRunning(false);
    }

    @Test
    public void failedMultiStepTransactionRollsBackMemberAndPost() throws Exception {
        manager.start(context);
        JSONObject member = createMember("事务回滚测试");
        long memberId = member.getJSONObject("member").getLong("id");
        AppDbHelper helper = AppDbHelper.getInstance(context);
        RuntimeException forced = new RuntimeException("test transaction failure");
        try {
            helper.withWriteTransaction(db -> {
                ContentValues renamed = new ContentValues();
                renamed.put("nickname", "不应保存的昵称");
                db.update("members", renamed, "id=?", new String[]{Long.toString(memberId)});
                ContentValues post = new ContentValues();
                post.put("owner_id", memberId);
                post.put("type", "FOUND");
                post.put("category", "KEYS");
                post.put("title", "不应保存的帖子");
                post.put("description", "事务失败时这一条帖子不应该写入数据库");
                post.put("location", "测试地点");
                post.put("status", "OPEN");
                post.put("created_at", System.currentTimeMillis());
                post.put("updated_at", System.currentTimeMillis());
                postId = db.insertOrThrow("posts", null, post);
                throw forced;
            });
            fail("Expected forced transaction failure");
        } catch (RuntimeException actual) {
            assertSame(forced, actual);
        }
        JSONObject restored = request("GET", "/api/session", null, member.getString("token"), 200);
        assertEquals("事务回滚测试", restored.getJSONObject("member").getString("nickname"));
        JSONObject posts = request("GET", "/api/me/posts", null, member.getString("token"), 200);
        assertEquals(0, posts.getJSONArray("items").length());
    }

    @Test
    public void restartKeepsIdentityPostClaimAndPhotoAndChecksPermissions() throws Exception {
        manager.start(context);
        String hostId = request("GET", "/api/ping", null, null, 200).getString("hostId");
        JSONObject owner = createMember("重启测试发布者");
        JSONObject applicant = createMember("重启测试申请人");
        String ownerToken = owner.getString("token");
        String applicantToken = applicant.getString("token");
        byte[] photo = jpeg();
        JSONObject body = new JSONObject().put("type", "FOUND").put("category", "KEYS")
                .put("title", "第六步重启测试钥匙").put("description", "用于检查重启之后图文数据是否仍然存在")
                .put("location", "测试图书馆").put("imageBase64", Base64.encodeToString(photo, Base64.NO_WRAP));
        JSONObject post = request("POST", "/api/posts", body, ownerToken, 200);
        postId = post.getLong("id");
        String imagePath = post.getString("imagePath");
        imageName = imagePath.substring("/images/".length());
        JSONObject claim = request("POST", "/api/posts/" + postId + "/claims",
                new JSONObject().put("proof", "有三把钥匙并且带着一个蓝色的挂件")
                        .put("contact", "test_contact"), applicantToken, 200);
        long claimId = claim.getLong("id");
        request("POST", "/api/claims/" + claimId + "/confirm", new JSONObject(), ownerToken, 200);
        assertArrayEquals(photo, getPhoto(imagePath));

        manager.stop();
        // 关闭并重开 SQLite 文件，避免只验证同一数据库连接的内存状态。
        synchronized (DatabaseLock.DB_LOCK) {
            AppDbHelper.getInstance(context).close();
        }
        manager.start(context);
        assertEquals(hostId, request("GET", "/api/ping", null, null, 200).getString("hostId"));
        for (JSONObject identity : new JSONObject[]{owner, applicant}) {
            JSONObject restored = request("GET", "/api/session", null, identity.getString("token"), 200);
            assertEquals(hostId, restored.getString("hostId"));
            assertEquals(identity.getJSONObject("member").getLong("id"),
                    restored.getJSONObject("member").getLong("id"));
        }
        JSONObject restoredPost = request("GET", "/api/posts/" + postId, null, null, 200);
        assertEquals("RESOLVED", restoredPost.getString("status"));
        assertEquals(imagePath, restoredPost.getString("imagePath"));
        assertArrayEquals(photo, getPhoto(imagePath));
        JSONObject repeated = request("POST", "/api/claims/" + claimId + "/confirm",
                new JSONObject(), ownerToken, 200);
        assertEquals("ACCEPTED", repeated.getString("status"));
        assertEquals("RESOLVED", repeated.getString("postStatus"));
        JSONObject mine = request("GET", "/api/me/claims", null, applicantToken, 200);
        assertEquals(claimId, mine.getJSONArray("items").getJSONObject(0).getLong("id"));
        request("GET", "/api/session", null, "invalid-token", 401);
        request("GET", "/api/posts/" + postId + "/claims", null, applicantToken, 403);
        request("POST", "/api/posts/" + postId + "/close", new JSONObject(), ownerToken, 409);
        request("GET", "/images/not-a-file.jpg", null, null, 404);
        request("GET", "/api/posts/" + postId + "/claims", null, ownerToken, 200);
    }

    private JSONObject createMember(String nickname) throws Exception {
        JSONObject member = request("POST", "/api/sessions",
                new JSONObject().put("nickname", nickname), null, 200);
        memberIds.add(member.getJSONObject("member").getLong("id"));
        return member;
    }

    private JSONObject request(String method, String path, JSONObject body, String token, int status)
            throws Exception {
        HttpURLConnection connection = connection(path);
        try {
            connection.setRequestMethod(method);
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            assertEquals(status, connection.getResponseCode());
            JSONObject reply = new JSONObject(new String(read(status >= 400
                    ? connection.getErrorStream() : connection.getInputStream()), StandardCharsets.UTF_8));
            assertEquals(status == 200, reply.getBoolean("success"));
            if (status != 200) {
                assertTrue(reply.isNull("data"));
                return reply;
            }
            return reply.getJSONObject("data");
        } finally {
            connection.disconnect();
        }
    }

    private byte[] getPhoto(String path) throws Exception {
        HttpURLConnection connection = connection(path);
        try {
            assertEquals(200, connection.getResponseCode());
            assertEquals("image/jpeg", connection.getContentType());
            return read(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection connection(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:8080" + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        return connection;
    }

    private byte[] read(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private byte[] jpeg() {
        Bitmap bitmap = Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            bitmap.eraseColor(0xff336699);
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output));
            return output.toByteArray();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            bitmap.recycle();
        }
    }

    private void awaitRunning(boolean expected) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (manager.isRunning() != expected && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(25);
        }
        assertEquals(expected, manager.isRunning());
    }

    private void awaitStartEnabled(ActivityScenario<HostActivity> scenario) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean enabled = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        do {
            scenario.onActivity(activity -> enabled.set(activity.findViewById(R.id.start_server).isEnabled()));
            if (enabled.get()) return;
            Thread.sleep(25);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        fail("新主机页面应允许手动重新启动");
    }
}
