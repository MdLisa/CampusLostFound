package com.example.campuslostfound;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.Base64;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.campuslostfound.api.LanApiClient;
import com.example.campuslostfound.data.AppDbHelper;
import com.example.campuslostfound.server.ServerManager;
import com.example.campuslostfound.server.ImageStore;
import com.example.campuslostfound.ui.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 专用模拟器的正式页面回归；真实 HTTP，测试数据均标注“演示”。 */
@RunWith(AndroidJUnit4.class)
public class FrontendFlowTest {
    private Context context;
    private final List<Long> members = new ArrayList<>();
    private final List<String> images = new ArrayList<>();
    private final ServerManager server = ServerManager.getInstance();

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        server.stop();
        server.start(context);
    }

    @After public void tearDown() throws Exception {
        server.stop();
        AppDbHelper helper = AppDbHelper.getInstance(context);
        helper.withWriteTransaction(db -> {
            for (long member : members) {
                String[] args = {Long.toString(member)};
                db.delete("claims", "applicant_id=? OR post_id IN (SELECT id FROM posts WHERE owner_id=?)",
                        new String[]{args[0], args[0]});
                db.delete("posts", "owner_id=?", args);
                db.delete("members", "id=?", args);
            }
            return null;
        });
        for (String name : images) new ImageStore(context, helper).deleteJpeg(name);
    }

    @Test public void connectionSearchDetailClaimAndCancellation() throws Exception {
        JSONObject owner = user("热心同学（演示）");
        JSONObject post = post(owner.getString("token"), "FOUND", "图书馆钥匙招领（演示）", true);
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            awaitDisplayed(R.id.open_client);
            onView(withId(R.id.open_client)).perform(click());
            awaitDisplayed(R.id.host_address_input);
            // 使用另一种回环地址写法，验证地址切换后的身份重置。
            onView(withId(R.id.host_address_input)).perform(replaceText("http://localhost:8080"), closeSoftKeyboard());
            onView(withId(R.id.connect_host)).perform(click());
            awaitDisplayed(R.id.nickname_input);
            onView(withId(R.id.nickname_input)).perform(replaceText("寻物同学（演示）"), closeSoftKeyboard());
            onView(withId(R.id.create_session)).perform(click());
            awaitDisplayed(R.id.open_business_debug);
            onView(withId(R.id.open_business_debug)).perform(click());
            awaitText(R.id.page_status, "共 1 条");
            awaitBusinessPage();
            onView(withId(R.id.search_keyword)).perform(replaceText("不存在的物品"), closeSoftKeyboard());
            onView(withId(R.id.search_posts)).perform(click());
            awaitText(R.id.page_status, "还没有符合");
            awaitBusinessPage();
            onView(withId(R.id.search_keyword)).perform(replaceText("钥匙"), closeSoftKeyboard());
            onView(withId(R.id.search_posts)).perform(click());
            awaitText(R.id.page_status, "共 1 条");
            onData(anything()).inAdapterView(withId(R.id.post_list)).atPosition(0).perform(click());
            awaitText(R.id.detail_title, "图书馆钥匙");
            awaitDisplayed(R.id.detail_photo);
            awaitBusinessPage();
            onView(withId(R.id.apply_claim)).perform(scrollTo(), click());
            onView(withId(R.id.claim_proof)).perform(replaceText("三把钥匙，蓝色圆环挂件，这是演示线索。"), closeSoftKeyboard());
            onView(withId(R.id.claim_contact)).perform(replaceText("演示联系号，仅测试"), closeSoftKeyboard());
            awaitDisplayed(R.id.claim_proof);
            onView(withText(R.string.ui_submit)).perform(click());
            awaitTextExists("申请已提交");
            JSONObject claim = request("GET", "/api/posts/" + post.getLong("id") + "/claims", null, owner.getString("token"))
                    .getJSONArray("items").getJSONObject(0);
            members.add(claim.getJSONObject("applicant").getLong("id"));
            assertEquals("PENDING", claim.getString("status"));
            onView(withText(R.string.ui_view_claims)).perform(click());
            awaitText(R.id.page_status, "共 1 条");
            awaitBusinessPage();
            onView(withId(R.id.claim_cancel)).perform(click());
            onView(withText(R.string.ui_confirm_action)).perform(click());
            awaitText(R.id.claim_state, "已取消");
            assertEquals("CANCELLED", request("GET", "/api/posts/" + post.getLong("id") + "/claims", null,
                    owner.getString("token")).getJSONArray("items").getJSONObject(0).getString("status"));
        }
    }

    @Test public void publishingValidationRecreationAndMyRecords() throws Exception {
        login("发布同学（演示）");
        try (ActivityScenario<PublishPostActivity> scenario = ActivityScenario.launch(PublishPostActivity.class)) {
            awaitEnabled(R.id.submit_post);
            select(R.id.publish_type, 1); select(R.id.publish_category, 3);
            onView(withId(R.id.publish_title)).perform(scrollTo(), replaceText("寻找蓝色钥匙（演示）"), closeSoftKeyboard());
            onView(withId(R.id.publish_description)).perform(scrollTo(), replaceText("周三下午在图书馆遗失，蓝色圆环挂件。仅用于页面演示。"), closeSoftKeyboard());
            onView(withId(R.id.publish_location)).perform(scrollTo(), replaceText("图书馆二楼"), closeSoftKeyboard());
            scenario.recreate();
            awaitEnabled(R.id.submit_post);
            onView(withId(R.id.publish_title)).check(matches(withText("寻找蓝色钥匙（演示）")));
            onView(withId(R.id.publish_title)).perform(scrollTo());
            awaitBusinessPage();
            onView(withId(R.id.submit_post)).perform(scrollTo(), click());
            awaitText(R.id.detail_title, "寻找蓝色钥匙");
            onView(withId(R.id.received_claims)).perform(scrollTo(), click());
            awaitText(R.id.page_status, "还没有收到申请");
            pressBack();
            awaitText(R.id.detail_title, "寻找蓝色钥匙");
            onView(withId(R.id.close_post)).perform(scrollTo(), click());
            onView(withText(R.string.ui_confirm_action)).perform(click());
            awaitText(R.id.detail_state, "已关闭");
        }
        try (ActivityScenario<MyRecordsActivity> ignored = ActivityScenario.launch(MyRecordsActivity.class)) {
            awaitText(R.id.page_status, "共 1 条");
            awaitBusinessPage();
        }
    }

    @Test public void receivedClaimsRejectionAndHandoverRefreshWholeList() throws Exception {
        JSONObject owner = login("归还同学（演示）");
        JSONObject post = post(owner.getString("token"), "FOUND", "图书馆的蓝色钥匙（演示）", true);
        JSONObject other = user("申请同学（演示）");
        JSONObject third = user("另一同学（演示）");
        JSONObject fourth = user("线索同学（演示）");
        for (JSONObject member : new JSONObject[]{other, third, fourth}) request("POST", "/api/posts/"+post.getLong("id")+"/claims",
                new JSONObject().put("proof", "三把钥匙挂在蓝色圆环上，这是模拟器演示证明。")
                        .put("contact", "演示联系号，不用于真实联系"), member.getString("token"));
        Intent intent = new Intent(context, ReceivedClaimsActivity.class).putExtra("postId", post.getLong("id"));
        try (ActivityScenario<ReceivedClaimsActivity> ignored = ActivityScenario.launch(intent)) {
            awaitText(R.id.page_status, "共 3 条");
            awaitBusinessPage();
            onView(withId(R.id.received_list)).perform(clickListButton(0, R.id.claim_reject));
            onView(withId(R.id.claim_reason)).perform(replaceText("物品特征暂不匹配（演示）"), closeSoftKeyboard());
            onView(withText(R.string.ui_reason_submit)).perform(click());
            awaitText(R.id.page_status, "共 3 条");
            onData(anything()).inAdapterView(withId(R.id.received_list)).atPosition(0)
                    .onChildView(withId(R.id.claim_note)).perform(scrollListControl())
                    .check(matches(withText(containsString("物品特征暂不匹配"))));
            onView(withId(R.id.received_list)).perform(clickListButton(1, R.id.claim_confirm));
            await(() -> onView(withText(R.string.ui_confirm_action)).check(matches(isDisplayed())));
            onView(withText(R.string.ui_confirm_action)).perform(click());
            awaitText(R.id.received_title, "已完成");
            JSONObject finalPost = request("GET", "/api/posts/"+post.getLong("id"), null, null);
            assertEquals("RESOLVED", finalPost.getString("status"));
            org.json.JSONArray claims = request("GET", "/api/posts/"+post.getLong("id")+"/claims", null, owner.getString("token")).getJSONArray("items");
            int accepted=0, pending=0;
            for(int i=0;i<claims.length();i++) {
                if("ACCEPTED".equals(claims.getJSONObject(i).getString("status"))) accepted++;
                if("PENDING".equals(claims.getJSONObject(i).getString("status"))) pending++;
            }
            assertEquals(1, accepted); assertEquals(0, pending);
            awaitBusinessPage();
        }
    }

    @Test public void readFailureAndRecoveryAreDistinctFromEmpty() throws Exception {
        JSONObject owner = login("网络测试同学（演示）");
        post(owner.getString("token"), "LOST", "寻找校园卡（演示）", false);
        try (ActivityScenario<PostListActivity> ignored = ActivityScenario.launch(PostListActivity.class)) {
            awaitText(R.id.page_status, "共 1 条");
            server.stop();
            onView(withId(R.id.search_posts)).perform(click());
            awaitText(R.id.page_status, "连接暂时不可用");
            awaitBusinessPage();
            server.start(context);
            onView(withId(R.id.page_retry)).perform(click());
            awaitText(R.id.page_status, "共 1 条");
        }
    }

    @Test public void lostPostOffersCluesAndOwnerCanResolveOutsideApp() throws Exception {
        JSONObject owner = user("失主同学（演示）");
        JSONObject lost = post(owner.getString("token"), "LOST", "寻找校园卡（演示）", false);
        login("提供线索同学（演示）");
        try (ActivityScenario<PostDetailActivity> ignored = ActivityScenario.launch(
                new Intent(context, PostDetailActivity.class).putExtra("postId", lost.getLong("id")))) {
            awaitText(R.id.detail_title, "寻找校园卡");
            onView(withId(R.id.apply_claim)).check(matches(withText(R.string.ui_offer)));
            onView(withId(R.id.apply_claim)).perform(scrollTo(), click());
            onView(withId(R.id.claim_proof_label)).check(matches(withText(R.string.ui_clue_label)));
            onView(withText(R.string.ui_cancel)).perform(click());
        }
        JSONObject self = login("站外找回同学（演示）");
        JSONObject own = post(self.getString("token"), "LOST", "遗失的书籍（演示）", false);
        try (ActivityScenario<PostDetailActivity> ignored = ActivityScenario.launch(
                new Intent(context, PostDetailActivity.class).putExtra("postId", own.getLong("id")))) {
            awaitText(R.id.detail_title, "遗失的书籍");
            onView(withId(R.id.resolve_post)).perform(scrollTo(), click());
            onView(withText(R.string.ui_confirm_action)).perform(click());
            awaitText(R.id.detail_state, "已完成");
            assertEquals("RESOLVED", request("GET", "/api/posts/"+own.getLong("id"), null, null).getString("status"));
        }
    }

    @Test public void selectedPhotoIsCompressedPreviewedAndPublished() throws Exception {
        JSONObject self = login("图片发布同学（演示）");
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "campus-frontend-demo.jpg");
        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        android.net.Uri uri = context.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        Bitmap source = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.rgb(46, 125, 100));
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) { source.compress(Bitmap.CompressFormat.JPEG, 95, out); }
        source.recycle();
        android.content.IntentFilter filter = new android.content.IntentFilter(Intent.ACTION_GET_CONTENT);
        filter.addDataType("image/*");
        filter.addCategory(Intent.CATEGORY_OPENABLE);
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        // 在选图返回边界提供真实内容地址，独立验证压缩与发布流程。
        android.app.Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(filter,
                new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, new Intent().setData(uri)), true);
        try (ActivityScenario<PublishPostActivity> ignored = ActivityScenario.launch(PublishPostActivity.class)) {
            awaitEnabled(R.id.submit_post); awaitDisplayed(R.id.business_body);
            select(R.id.publish_type, 2); select(R.id.publish_category, 3);
            onView(withId(R.id.publish_title)).perform(scrollTo(), replaceText("带图钥匙招领（演示）"), closeSoftKeyboard());
            onView(withId(R.id.publish_description)).perform(scrollTo(), replaceText("这是带图发布回归，原图最长边 1600 像素。"), closeSoftKeyboard());
            onView(withId(R.id.publish_location)).perform(scrollTo(), replaceText("校园图书馆"), closeSoftKeyboard());
            onView(withId(R.id.choose_photo)).perform(scrollTo(), click());
            awaitText(R.id.publish_photo_status, "800");
            onView(withId(R.id.publish_photo)).perform(scrollTo()).check(matches(isDisplayed()));
            awaitBusinessPage();
            onView(withId(R.id.submit_post)).perform(scrollTo(), click());
            awaitText(R.id.detail_title, "带图钥匙"); awaitDisplayed(R.id.detail_photo);
            JSONObject post = request("GET", "/api/me/posts", null, self.getString("token")).getJSONArray("items").getJSONObject(0);
            assertFalse(post.isNull("imagePath"));
            images.add(post.getString("imagePath").substring("/images/".length()));
        } finally {
            instrumentation.removeMonitor(monitor);
            context.getContentResolver().delete(uri, null, null);
        }
    }

    @Test public void failedApplicationPreservesDraftAndChecksMyClaimsBeforeRetry() throws Exception {
        JSONObject owner = user("物主（演示）");
        JSONObject post = post(owner.getString("token"), "FOUND", "断网认领测试（演示）", false);
        login("认领测试同学（演示）");
        try (ActivityScenario<PostDetailActivity> ignored = ActivityScenario.launch(
                new Intent(context, PostDetailActivity.class).putExtra("postId", post.getLong("id")))) {
            awaitText(R.id.detail_title, "断网认领测试");
            onView(withId(R.id.apply_claim)).perform(scrollTo(), click());
            onView(withId(R.id.claim_proof)).perform(replaceText("三把钥匙，蓝色挂件，保存这段演示特征。"), closeSoftKeyboard());
            onView(withId(R.id.claim_contact)).perform(replaceText("演示联系号"), closeSoftKeyboard());
            server.stop();
            onView(withText(R.string.ui_submit)).perform(click());
            awaitText(R.id.page_status, "操作结果尚未确认");
            onView(withId(R.id.claim_proof)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            onView(withId(R.id.apply_claim)).check(matches(not(isEnabled())));
            awaitBusinessPage();
            server.start(context);
            onView(withText(R.string.ui_view_records)).perform(click());
            awaitText(R.id.page_status, "还没有申请记录");
            onView(withId(R.id.mode_claims)).check(matches(isChecked()));
            pressBack();
            awaitEnabled(R.id.apply_claim);
            onView(withId(R.id.apply_claim)).perform(scrollTo(), click());
            onView(withId(R.id.claim_proof)).check(matches(withText("三把钥匙，蓝色挂件，保存这段演示特征。")));
            onView(withId(R.id.claim_contact)).check(matches(withText("演示联系号")));
            onView(withText(R.string.ui_cancel)).perform(click());
        }
    }

    @Test public void failedRejectionPreservesReasonAndReloadsReceivedClaims() throws Exception {
        JSONObject owner = login("发布者（演示）");
        JSONObject post = post(owner.getString("token"), "FOUND", "断网处理测试（演示）", false);
        JSONObject other = user("申请人（演示）");
        request("POST", "/api/posts/"+post.getLong("id")+"/claims", new JSONObject()
                .put("proof", "三把钥匙，蓝色挂件，这是演示特征证明。")
                .put("contact", "演示联系号"), other.getString("token"));
        try (ActivityScenario<ReceivedClaimsActivity> ignored = ActivityScenario.launch(
                new Intent(context, ReceivedClaimsActivity.class).putExtra("postId", post.getLong("id")))) {
            awaitText(R.id.page_status, "共 1 条");
            onView(withId(R.id.received_list)).perform(clickListButton(0, R.id.claim_reject));
            onView(withId(R.id.claim_reason)).perform(replaceText("演示特征不匹配，保留此原因"), closeSoftKeyboard());
            server.stop();
            onView(withText(R.string.ui_reason_submit)).perform(click());
            awaitText(R.id.page_status, "操作结果尚未确认");
            onView(withId(R.id.claim_reason)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            awaitBusinessPage();
            server.start(context);
            onView(withText(R.string.ui_view_records)).perform(click());
            awaitText(R.id.page_status, "共 1 条");
            onView(withId(R.id.received_title)).check(matches(withText(containsString("断网处理测试"))));
            onView(withId(R.id.received_list)).perform(clickListButton(0, R.id.claim_reject));
            onView(withId(R.id.claim_reason)).check(matches(withText("演示特征不匹配，保留此原因")));
            onView(withText(R.string.ui_cancel)).perform(click());
        }
    }

    @Test public void failedPublishChecksMyPostsAndKeepsForm() throws Exception {
        JSONObject self = login("发布恢复同学（演示）");
        try (ActivityScenario<PublishPostActivity> ignored = ActivityScenario.launch(PublishPostActivity.class)) {
            awaitEnabled(R.id.submit_post); awaitDisplayed(R.id.business_body);
            select(R.id.publish_type, 2); select(R.id.publish_category, 3);
            onView(withId(R.id.publish_title)).perform(scrollTo(), replaceText("断网发布草稿（演示）"), closeSoftKeyboard());
            onView(withId(R.id.publish_description)).perform(scrollTo(), replaceText("网络恢复后需要先核对我的发布，不能自动重复发送。"), closeSoftKeyboard());
            onView(withId(R.id.publish_location)).perform(scrollTo(), replaceText("校园图书馆"), closeSoftKeyboard());
            server.stop();
            onView(withId(R.id.submit_post)).perform(scrollTo(), click());
            awaitText(R.id.page_status, "操作结果尚未确认");
            onView(withId(R.id.submit_post)).check(matches(not(isEnabled())));
            server.start(context);
            onView(withText(R.string.ui_view_records)).perform(click());
            awaitText(R.id.page_status, "你还没有符合条件的发布");
            onView(withId(R.id.mode_posts)).check(matches(isChecked()));
            pressBack();
            awaitDisplayed(R.id.business_body); awaitEnabled(R.id.submit_post);
            onView(withId(R.id.publish_title)).check(matches(withText("断网发布草稿（演示）")));
            onView(withId(R.id.page_status)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            assertEquals(0, request("GET", "/api/me/posts", null, self.getString("token")).getJSONArray("items").length());
        }
    }

    private JSONObject user(String name) throws Exception {
        JSONObject user=request("POST", "/api/sessions", new JSONObject().put("nickname", name), null);
        members.add(user.getJSONObject("member").getLong("id"));return user;
    }
    private JSONObject login(String name) throws Exception {
        LanApiClient client = new LanApiClient(context);
        CountDownLatch latch = new CountDownLatch(1);
        JSONObject[] result = {null};
        client.connect("http://127.0.0.1:8080", new LanApiClient.Callback() {
            @Override public void onSuccess(JSONObject data) {
                client.createSession(name, new LanApiClient.Callback() {
                    @Override public void onSuccess(JSONObject data) { result[0]=data;latch.countDown(); }
                    @Override public void onFailure(LanApiClient.ApiError error) { latch.countDown(); }
                });
            }
            @Override public void onFailure(LanApiClient.ApiError error) { latch.countDown(); }
        });
        assertTrue(latch.await(15,TimeUnit.SECONDS));client.close();assertNotNull(result[0]);
        members.add(result[0].getJSONObject("member").getLong("id"));return result[0];
    }
    private JSONObject post(String token,String type,String title,boolean image) throws Exception {
        JSONObject body=new JSONObject().put("type",type).put("title",title)
                .put("description","今天下午在图书馆二楼看到的物品，请描述特征。此条为模拟器演示数据。")
                .put("category","KEYS").put("location","图书馆二楼自习区");
        if(image) {
            Bitmap bitmap=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.rgb(222,238,230));
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.rgb(0,107,94));paint.setStrokeWidth(18);
            paint.setStyle(Paint.Style.STROKE);canvas.drawCircle(240,195,65,paint);
            canvas.drawLine(280,240,410,365,paint);canvas.drawLine(360,315,390,285,paint);
            paint.setStyle(Paint.Style.FILL);paint.setTextSize(30);canvas.drawText("演示",40,445,paint);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,85,bytes);bitmap.recycle();
            body.put("imageBase64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
        }
        JSONObject post=request("POST","/api/posts",body,token);
        if(!post.isNull("imagePath"))images.add(post.getString("imagePath").substring("/images/".length()));
        return post;
    }
    private JSONObject request(String method,String path,JSONObject body,String token) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:8080"+path).openConnection();
        connection.setRequestMethod(method);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
        if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
        if(body!=null) {
            connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
            try(OutputStream output=connection.getOutputStream()){output.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        }
        int status=connection.getResponseCode();
        try(InputStream input=status==200?connection.getInputStream():connection.getErrorStream()) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);
            assertEquals(bytes.toString("UTF-8"),200,status);
            return new JSONObject(bytes.toString("UTF-8")).getJSONObject("data");
        } finally {connection.disconnect();}
    }
    private void select(int id,int position) {
        onView(withId(id)).perform(scrollTo(),click());onData(anything()).atPosition(position).perform(click());
    }

    /** Scrolls a long ListView row like a user would; the click still requires 90% visibility. */
    private androidx.test.espresso.ViewAction scrollListControl() {
        return new androidx.test.espresso.ViewAction() {
            @Override public org.hamcrest.Matcher<android.view.View> getConstraints() { return isAssignableFrom(android.view.View.class); }
            @Override public String getDescription() { return "scroll the containing ListView to this action"; }
            @Override public void perform(androidx.test.espresso.UiController ui, android.view.View view) {
                android.view.ViewParent parent = view.getParent();
                while (parent != null && !(parent instanceof android.widget.ListView)) parent = parent.getParent();
                assertNotNull(parent);
                android.widget.ListView list = (android.widget.ListView) parent;
                for (int i = 0; i < 12; i++) {
                    int[] buttonLocation = new int[2], listLocation = new int[2];
                    view.getLocationOnScreen(buttonLocation); list.getLocationOnScreen(listLocation);
                    int bottom = buttonLocation[1] + view.getHeight();
                    int viewportBottom = listLocation[1] + list.getHeight() - list.getPaddingBottom();
                    int delta = bottom > viewportBottom ? bottom - viewportBottom + 8
                            : buttonLocation[1] < listLocation[1] ? buttonLocation[1] - listLocation[1] - 8 : 0;
                    if (delta == 0) return;
                    list.scrollListBy(delta);
                    ui.loopMainThreadForAtLeast(50);
                }
            }
        };
    }

    private androidx.test.espresso.ViewAction clickListButton(int position, int buttonId) {
        return new androidx.test.espresso.ViewAction() {
            @Override public org.hamcrest.Matcher<android.view.View> getConstraints() { return isAssignableFrom(android.widget.ListView.class); }
            @Override public String getDescription() { return "scroll a long row and tap its visible action"; }
            @Override public void perform(androidx.test.espresso.UiController ui, android.view.View view) {
                android.widget.ListView list = (android.widget.ListView) view;
                list.setSelection(position);
                ui.loopMainThreadForAtLeast(100);
                for (int i = 0; i < 20; i++) {
                    android.view.View row = list.getChildAt(position - list.getFirstVisiblePosition());
                    if (row == null) { list.setSelection(position); ui.loopMainThreadForAtLeast(50); continue; }
                    android.view.View button = row.findViewById(buttonId);
                    assertNotNull(button);
                    assertEquals(android.view.View.VISIBLE, button.getVisibility());
                    assertTrue(button.isEnabled());
                    android.graphics.Rect visible = new android.graphics.Rect();
                    boolean shown = button.getGlobalVisibleRect(visible);
                    if (shown && visible.height() >= button.getHeight() * 0.95f
                            && visible.width() >= button.getWidth() * 0.95f) {
                        click().perform(ui, button);
                        ui.loopMainThreadUntilIdle();
                        return;
                    }
                    int[] location = new int[2], viewport = new int[2];
                    button.getLocationOnScreen(location); list.getLocationOnScreen(viewport);
                    int desired = viewport[1] + (list.getHeight() - button.getHeight()) / 2;
                    list.scrollListBy(location[1] - desired);
                    ui.loopMainThreadForAtLeast(50);
                }
                fail("Action could not be scrolled into the viewport");
            }
        };
    }
    private void awaitDisplayed(int id) { await(() -> onView(withId(id)).check(matches(isDisplayed()))); }
    private void awaitEnabled(int id) { await(() -> onView(withId(id)).check(matches(isEnabled()))); }
    private void awaitText(int id,String text) { await(() -> onView(withId(id)).check(matches(withText(containsString(text))))); }
    private void awaitTextExists(String text) { await(() -> onView(withText(containsString(text))).check(matches(isDisplayed()))); }
    private void await(Runnable check) {
        Throwable failure=null;long end=SystemClock.uptimeMillis()+15000;
        while(SystemClock.uptimeMillis()<end) {
            try {check.run();return;} catch(AssertionError|androidx.test.espresso.NoMatchingViewException
                    |androidx.test.espresso.NoActivityResumedException e) {failure=e;SystemClock.sleep(100);}
        }
        throw new AssertionError("UI state did not arrive",failure);
    }
    private void awaitBusinessPage() {
        await(() -> onView(withId(R.id.page_progress))
                .check(matches(withEffectiveVisibility(Visibility.GONE))));
        awaitDisplayed(R.id.business_body);
    }
}
