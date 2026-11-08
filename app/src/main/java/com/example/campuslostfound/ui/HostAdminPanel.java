package com.example.campuslostfound.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.example.campuslostfound.R;
import com.example.campuslostfound.data.AppDbHelper;
import com.example.campuslostfound.data.HostAdminRepository;
import com.example.campuslostfound.server.ImageStore;
import com.example.campuslostfound.common.ImageCompressor;
import com.example.campuslostfound.common.ImageRules;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 在 HostActivity 内切换管理内容，不触发主机页面 onStop。 */
final class HostAdminPanel {
    private final HostActivity activity;
    private final HostAdminRepository repository;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Spinner section, type, category, status;
    private final EditText keyword;
    private final TextView message;
    private final LinearLayout results;
    private boolean active, busy;
    private int requestId;
    private int selectedSection;
    private int memberMode;
    private long memberId, reasonPost;
    private String memberName = "", reasonDraft = "";
    private boolean deleting;
    private AlertDialog dialog;
    private EditText reasonInput;

    HostAdminPanel(HostActivity activity, Bundle state) {
        this.activity = activity;
        repository = new HostAdminRepository(AppDbHelper.getInstance(activity));
        section = activity.findViewById(R.id.admin_section);
        type = activity.findViewById(R.id.admin_type);
        category = activity.findViewById(R.id.admin_category);
        status = activity.findViewById(R.id.admin_status);
        keyword = activity.findViewById(R.id.admin_keyword);
        message = activity.findViewById(R.id.admin_message);
        results = activity.findViewById(R.id.admin_results);
        UiSupport.options(type, R.array.ui_types);
        UiSupport.options(category, R.array.ui_categories);
        UiSupport.options(status, R.array.admin_post_statuses);
        if (state != null) {
            selectedSection = state.getInt("admin_section");
            memberMode = state.getInt("admin_member_mode");
            memberId = state.getLong("admin_member_id");
            memberName = state.getString("admin_member_name", "");
            reasonPost = state.getLong("admin_reason_post");
            reasonDraft = state.getString("admin_reason", "");
            deleting = state.getBoolean("admin_reason_delete");
        }
        section.setSelection(selectedSection);
        section.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (selectedSection == position) return;
                selectedSection = position;
                memberMode = 0;
                if (active) refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        activity.findViewById(R.id.admin_refresh).setOnClickListener(v -> {
            memberMode = 0;
            refresh();
        });
    }

    void onStart() { active = true; busy = false; refresh(); }
    void onStop() {
        active = false;
        requestId++;
        captureReason();
        if (dialog != null) dialog.dismiss();
    }
    void onDestroy() { worker.shutdown(); }

    void saveState(Bundle out) {
        captureReason();
        out.putInt("admin_section", selectedSection);
        out.putInt("admin_member_mode", memberMode);
        out.putLong("admin_member_id", memberId);
        out.putString("admin_member_name", memberName);
        out.putLong("admin_reason_post", reasonPost);
        out.putString("admin_reason", reasonDraft);
        out.putBoolean("admin_reason_delete", deleting);
    }

    private void captureReason() {
        if (reasonInput != null) reasonDraft = reasonInput.getText().toString();
    }

    private interface Query { JSONObject run() throws Exception; }
    private interface Renderer { void render(JSONObject data) throws Exception; }

    private void query(Query query, Renderer renderer) {
        if (!active || busy) return;
        int round = ++requestId;
        setBusy(true);
        message.setText(R.string.ui_loading);
        worker.execute(() -> {
            JSONObject data = null;
            String failure = null;
            try { data = query.run(); }
            catch (Exception e) {
                failure = UiSupport.errorMessage(activity, e.getMessage(), R.string.ui_request_failed);
            }
            JSONObject value = data;
            String error = failure;
            activity.runOnUiThread(() -> {
                if (!active || round != requestId || activity.isDestroyed()) return;
                setBusy(false);
                if (error != null) { message.setText(activity.getString(R.string.admin_failed, error)); return; }
                try { renderer.render(value); }
                catch (Exception e) { message.setText(R.string.ui_request_failed); }
            });
        });
    }

    private void refresh() {
        if (!active || busy) return;
        filters();
        results.removeAllViews();
        if (selectedSection == 0) {
            query(repository::overview, data -> {
                message.setText("");
                text(results, activity.getString(R.string.admin_counts, data.getLong("members"),
                        data.getLong("posts"), data.getLong("open"), data.getLong("resolved"),
                        data.getLong("closed"), data.getLong("pending"), data.getLong("deleted")));
            });
        } else if (selectedSection == 1) {
            String selectedType = UiSupport.TYPES[type.getSelectedItemPosition()];
            String selectedCategory = UiSupport.CATEGORIES[category.getSelectedItemPosition()];
            String selectedStatus = new String[]{null, "OPEN", "RESOLVED", "CLOSED", "DELETED"}[status.getSelectedItemPosition()];
            String search = keyword.getText().toString();
            query(() -> repository.listPosts(selectedType, selectedCategory, selectedStatus, search, null), this::renderPosts);
        } else if (selectedSection == 2 && memberMode == 0) {
            String search = keyword.getText().toString();
            query(() -> repository.listMembers(search), this::renderMembers);
        } else if (selectedSection == 2) {
            text(results, activity.getString(R.string.admin_member_scope, memberName, memberId));
            button(results, R.string.admin_all_members, () -> { memberMode = 0; refresh(); });
            if (memberMode == 1) query(() -> repository.listPosts(null, null, null, null, memberId), this::renderPosts);
            else query(() -> repository.memberClaims(memberId), this::renderClaims);
        } else {
            query(repository::logs, this::renderLogs);
        }
    }

    private void filters() {
        activity.findViewById(R.id.admin_filters).setVisibility(
                selectedSection == 1 || (selectedSection == 2 && memberMode == 0) ? View.VISIBLE : View.GONE);
        activity.findViewById(R.id.admin_post_filters).setVisibility(selectedSection == 1 ? View.VISIBLE : View.GONE);
    }

    private void setBusy(boolean value) {
        busy = value;
        activity.findViewById(R.id.admin_progress).setVisibility(value ? View.VISIBLE : View.GONE);
        for (int id : new int[]{R.id.admin_section, R.id.admin_keyword, R.id.admin_type,
                R.id.admin_category, R.id.admin_status, R.id.admin_refresh}) activity.findViewById(id).setEnabled(!value);
        enableTree(results, !value);
    }
    private void enableTree(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enableTree(group.getChildAt(i), enabled);
        }
    }

    private JSONArray items(JSONObject data) {
        JSONArray items = data.optJSONArray("items");
        if (items == null) items = new JSONArray();
        message.setText(data.optBoolean("hasMore") ? activity.getString(R.string.admin_more)
                : items.length() == 0 ? activity.getString(R.string.admin_empty)
                : activity.getString(R.string.admin_count, items.length()));
        return items;
    }

    private void renderPosts(JSONObject data) throws Exception {
        JSONArray items = items(data);
        for (int i = 0; i < items.length(); i++) {
            JSONObject post = items.getJSONObject(i);
            JSONObject owner = post.getJSONObject("owner");
            LinearLayout card = card();
            text(card, activity.getString(R.string.admin_post_row, post.getLong("id"), post.getString("title"),
                    activity.getString(UiSupport.typeLabel(post.getString("type"))),
                    activity.getString(post.optBoolean("deleted") ? R.string.admin_deleted : UiSupport.stateLabel(post.getString("status"))),
                    owner.getString("nickname"), owner.getLong("id"), post.getString("location"),
                    UiSupport.date(post.getLong("createdAt"))));
            button(card, R.string.admin_view_post, () -> viewPost(post.optLong("id")));
        }
    }

    private void renderMembers(JSONObject data) throws Exception {
        JSONArray items = items(data);
        for (int i = 0; i < items.length(); i++) {
            JSONObject member = items.getJSONObject(i);
            LinearLayout card = card();
            text(card, activity.getString(R.string.admin_member_row, member.getString("nickname"),
                    member.getLong("id"), member.getLong("postCount"), member.getLong("claimCount"),
                    UiSupport.date(member.getLong("createdAt"))));
            button(card, R.string.admin_member_posts, () -> memberRecords(member, 1));
            button(card, R.string.admin_member_claims, () -> memberRecords(member, 2));
        }
    }
    private void memberRecords(JSONObject member, int mode) {
        memberId = member.optLong("id"); memberName = member.optString("nickname"); memberMode = mode;
        refresh();
    }

    private void renderClaims(JSONObject data) throws Exception {
        JSONArray items = items(data);
        for (int i = 0; i < items.length(); i++) {
            JSONObject claim = items.getJSONObject(i);
            LinearLayout card = card();
            text(card, activity.getString(R.string.admin_claim_row, claim.getLong("id"),
                    claim.getString("postTitle"),
                    activity.getString(UiSupport.stateLabel(claim.getString("postStatus"))),
                    activity.getString(UiSupport.stateLabel(claim.getString("status"))),
                    UiSupport.date(claim.getLong("createdAt"))));
            String note = UiSupport.text(claim, "note");
            if (!note.isEmpty()) text(card, note);
            button(card, R.string.admin_view_post, () -> viewPost(claim.optLong("postId")));
        }
    }

    private void renderLogs(JSONObject data) throws Exception {
        JSONArray items = items(data);
        for (int i = 0; i < items.length(); i++) {
            JSONObject log = items.getJSONObject(i);
            LinearLayout card = card();
            text(card, activity.getString(R.string.admin_log_row, log.getLong("postId"),
                    log.getString("postTitle"), log.getString("reason"), UiSupport.date(log.getLong("createdAt")),
                    activity.getString("DELETE".equals(log.optString("action")) ? R.string.admin_delete_action : R.string.admin_close_action)));
            button(card, R.string.admin_view_post, () -> viewPost(log.optLong("postId")));
        }
    }

    private void viewPost(long id) {
        query(() -> repository.getPost(id), post -> {
            message.setText("");
            android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
            LinearLayout body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            int padding = dp(20);
            body.setPadding(padding, padding, padding, padding);
            scroll.addView(body);
            text(body, activity.getString(R.string.admin_detail,
                    activity.getString(post.optBoolean("deleted") ? R.string.admin_deleted : UiSupport.stateLabel(post.getString("status"))),
                    post.getString("description"),
                    activity.getString(R.string.ui_owner, post.getJSONObject("owner").getString("nickname"))));
            ImageView image = new ImageView(activity);
            image.setContentDescription(activity.getString(R.string.photo_remote_description));
            image.setAdjustViewBounds(true);
            image.setMaxHeight(dp(240));
            body.addView(image, new LinearLayout.LayoutParams(-1, -2));
            TextView imageStatus = text(body, "");
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                    .setTitle(post.getString("title")).setView(scroll)
                    .setNegativeButton(R.string.admin_done, null);
            if (!post.optBoolean("deleted")) {
                builder.setNeutralButton(R.string.admin_delete_post, (d, which) -> actionForm(id, true));
                if ("OPEN".equals(post.getString("status")))
                    builder.setPositiveButton(R.string.admin_close_post, (d, which) -> actionForm(id, false));
            }
            dialog = builder.create();
            AlertDialog shown = dialog;
            dialog.show();
            String path = UiSupport.text(post, "imagePath");
            if (!path.isEmpty()) loadLocalPhoto(path, shown, image, imageStatus);
            else imageStatus.setText(R.string.ui_no_photo);
        });
    }

    private void loadLocalPhoto(String path, AlertDialog shown, ImageView image, TextView statusView) {
        statusView.setText(R.string.photo_loading);
        int round = requestId;
        worker.execute(() -> {
            android.graphics.Bitmap bitmap = null;
            try (InputStream input = new ImageStore(activity.getApplicationContext(),
                    AppDbHelper.getInstance(activity)).openAdminJpeg(path.substring("/images/".length()));
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (bytes.size() + n > ImageRules.MAX_JPEG_BYTES) throw new java.io.IOException();
                    bytes.write(buffer, 0, n);
                }
                bitmap = ImageCompressor.decodeJpeg(bytes.toByteArray());
            } catch (Exception ignored) { }
            android.graphics.Bitmap result = bitmap;
            activity.runOnUiThread(() -> {
                if (!active || round != requestId || !shown.isShowing()) {
                    if (result != null) result.recycle();
                    return;
                }
                if (result == null) statusView.setText(R.string.ui_image_failed);
                else { image.setImageBitmap(result); statusView.setText(""); }
            });
        });
    }

    private void actionForm(long id, boolean delete) {
        if (!active || busy) return;
        if (reasonPost != id || deleting != delete) reasonDraft = "";
        deleting = delete;
        reasonPost = id;
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), dp(12), dp(20), dp(12));
        text(fields, activity.getString(delete ? R.string.admin_delete_warning : R.string.admin_close_warning));
        reasonInput = new EditText(activity);
        reasonInput.setHint(delete ? R.string.admin_delete_reason : R.string.admin_close_reason);
        reasonInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        reasonInput.setText(reasonDraft);
        fields.addView(reasonInput);
        dialog = new MaterialAlertDialogBuilder(activity).setTitle(delete ? R.string.admin_delete_post : R.string.admin_close_post)
                .setView(fields).setNegativeButton(R.string.ui_cancel, null)
                .setPositiveButton(delete ? R.string.admin_delete_submit : R.string.admin_close_submit, null).create();
        dialog.setOnDismissListener(d -> { captureReason(); reasonInput = null; });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!active || busy || !UiSupport.validate(reasonInput, 2, 100)) return;
            captureReason();
            String reason = reasonDraft;
            dialog.dismiss();
            query(() -> {
                if (delete) repository.deletePost(id, reason); else repository.closePost(id, reason);
                return new JSONObject();
            }, data -> {
                reasonDraft = "";
                refresh();
                com.google.android.material.snackbar.Snackbar.make(activity.findViewById(R.id.host_root),
                        delete ? R.string.admin_deleted_success : R.string.admin_closed, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
            });
        }));
        dialog.show();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_field);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(16);
        results.addView(card, params);
        return card;
    }
    private TextView text(LinearLayout parent, String value) {
        TextView view = new TextView(activity);
        view.setTextAppearance(R.style.UiBody);
        view.setText(value);
        view.setTextIsSelectable(true);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private void button(LinearLayout parent, int label, Runnable action) {
        MaterialButton button = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        parent.addView(button, params);
        button.setOnClickListener(v -> { if (active && !busy) action.run(); });
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
