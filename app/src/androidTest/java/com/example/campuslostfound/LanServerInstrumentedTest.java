package com.example.campuslostfound;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.campuslostfound.server.HostIdentity;
import com.example.campuslostfound.server.ServerManager;
import com.example.campuslostfound.ui.HostActivity;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;

@RunWith(AndroidJUnit4.class)
public class LanServerInstrumentedTest {
    private final ServerManager manager = ServerManager.getInstance();
    private Context context;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Android 17 的运行时权限也适用于测试中启动的主机。
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            android.os.ParcelFileDescriptor result = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().executeShellCommand(
                            "pm grant " + context.getPackageName()
                                    + " android.permission.ACCESS_LOCAL_NETWORK");
            try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(result)) {
                while (input.read() != -1) { }
            }
        }
        manager.stop();
    }

    @After
    public void tearDown() {
        manager.stop();
    }

    @Test
    public void pingMatchesContractAndIdentitySurvivesRestart() throws Exception {
        manager.start(context);
        JSONObject first = request("/api/ping", 200);
        assertTrue(first.getBoolean("success"));
        assertEquals("ok", first.getString("message"));
        JSONObject data = first.getJSONObject("data");
        assertEquals("campus-lost-found", data.getString("app"));
        assertEquals(2, data.getInt("protocolVersion"));
        String hostId = data.getString("hostId");
        assertFalse(hostId.isEmpty());
        manager.stop();
        manager.start(context);
        assertEquals(hostId, request("/api/ping", 200).getJSONObject("data").getString("hostId"));
        assertEquals(hostId, HostIdentity.getOrCreate(context));
    }

    @Test
    public void unknownRouteUsesJson404() throws Exception {
        manager.start(context);
        JSONObject error = request("/api/not-found", 404);
        assertFalse(error.getBoolean("success"));
        assertFalse(error.getString("message").isEmpty());
        assertTrue(error.isNull("data"));
    }

    @Test
    public void repeatedStartIsSafeAndStopReleasesPort() throws Exception {
        manager.start(context);
        manager.start(context);
        request("/api/ping", 200);
        manager.stop();
        manager.stop();
        assertFalse(manager.isRunning());
        try (Socket socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", 8080), 500);
                fail("停止后不应继续接受连接");
            } catch (IOException expected) {
                // 已停止监听。
            }
        }
        manager.start(context);
        request("/api/ping", 200);
    }

    @Test
    public void occupiedPortFailsWithoutLeavingServerRunning() throws Exception {
        try (ServerSocket occupied = new ServerSocket(8080)) {
            try {
                manager.start(context);
                fail("端口占用应报告启动失败");
            } catch (IOException expected) {
                assertFalse(manager.isRunning());
            }
        }
        manager.start(context);
        request("/api/ping", 200);
    }

    @Test
    public void leavingHostPageStopsServerAndReturningNeedsManualStart() throws Exception {
        try (ActivityScenario<HostActivity> scenario = ActivityScenario.launch(HostActivity.class)) {
            scenario.onActivity(activity -> activity.findViewById(R.id.start_server).performClick());
            awaitRunning(true);
            request("/api/ping", 200);
            scenario.moveToState(Lifecycle.State.CREATED);
            awaitRunning(false);
            scenario.moveToState(Lifecycle.State.RESUMED);
            assertFalse(manager.isRunning());
        }
    }

    private void awaitRunning(boolean expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (manager.isRunning() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(expected, manager.isRunning());
    }

    private JSONObject request(String path, int expectedStatus) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:8080" + path).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        try {
            assertEquals(expectedStatus, connection.getResponseCode());
            assertTrue(connection.getContentType().contains("application/json"));
            try (InputStream input = expectedStatus >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                return new JSONObject(output.toString("UTF-8"));
            }
        } finally {
            connection.disconnect();
        }
    }
}
