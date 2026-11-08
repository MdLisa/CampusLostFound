package com.example.campuslostfound.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.Spinner;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.example.campuslostfound.R;
import org.json.JSONObject;

public class MyRecordsActivity extends BusinessActivity implements ClaimAdapter.Actions {
    private Spinner status;
    private ListView list;
    private PostAdapter posts;
    private ClaimAdapter claims;
    private boolean claimsMode;
    private int queryRound;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_my_records);
        bindPage(R.string.ui_my_records);
        status = findViewById(R.id.record_status);
        list = findViewById(R.id.record_list);
        posts = new PostAdapter(this);
        claims = new ClaimAdapter(this, false, this);
        claimsMode = state == null ? getIntent().getBooleanExtra("claims", false) : state.getBoolean("claims_mode");
        ((RadioGroup) findViewById(R.id.record_mode)).check(claimsMode ? R.id.mode_claims : R.id.mode_posts);
        configureMode();
        ((RadioGroup) findViewById(R.id.record_mode)).setOnCheckedChangeListener((group, checked) -> {
            claimsMode = checked == R.id.mode_claims;
            configureMode();
            if (ready && !busy) loadRecords();
        });
        list.setOnItemClickListener((parent, view, position, id) -> { if (!claimsMode) openPost(id); });
        findViewById(R.id.refresh_records).setOnClickListener(view -> loadRecords());
    }

    private void configureMode() {
        UiSupport.options(status, claimsMode ? R.array.ui_claim_statuses : R.array.ui_post_statuses);
        list.setAdapter(claimsMode ? claims : posts);
    }

    @Override protected void onReady() { loadRecords(); }

    private void loadRecords() {
        if (!ready || busy) return;
        int round = ++queryRound;
        setBusy(true);
        showState(getString(R.string.ui_loading), false);
        String filter = (claimsMode ? UiSupport.CLAIM_STATUSES : UiSupport.POST_STATUSES)[status.getSelectedItemPosition()];
        com.example.campuslostfound.api.LostFoundApi.Callback callback = (code, response, error) -> {
            if (!visible || round != queryRound) return;
            JSONObject data = result(code, response, error, false);
            if (claimsMode) claims.replace(data == null ? null : data.optJSONArray("items"), false);
            else posts.replace(data == null ? null : data.optJSONArray("items"));
            if (data == null) return;
            if (claimsMode) clearWriteUncertainty();
            if (getIntent().getBooleanExtra("verifyWriteOutcome", false)
                    && claimsMode == getIntent().getBooleanExtra("claims", false)) setResult(RESULT_OK);
            int count = claimsMode ? claims.getCount() : posts.getCount();
            if (count == 0) showState(getString(claimsMode ? R.string.ui_empty_claims : R.string.ui_empty_my_posts), false);
            else showState(getString(data.optBoolean("hasMore") ? R.string.ui_more : R.string.ui_records_count, count), false);
        };
        if (claimsMode) api.getMyClaims(filter, callback); else api.getMyPosts(filter, callback);
    }

    @Override protected void onBusyChanged() {
        if (status == null) return;
        boolean enabled = ready && !busy;
        status.setEnabled(enabled);
        findViewById(R.id.mode_posts).setEnabled(enabled);
        findViewById(R.id.mode_claims).setEnabled(enabled);
        findViewById(R.id.refresh_records).setEnabled(enabled);
        claims.enableActions(enabled && !hasUncertainWrite());
    }

    @Override protected void reviewUncertainWrite() {
        if (!claimsMode) ((RadioGroup) findViewById(R.id.record_mode)).check(R.id.mode_claims);
        else loadRecords();
    }

    @Override public void openPost(long id) {
        startActivity(new Intent(this, PostDetailActivity.class).putExtra("postId", id));
    }

    @Override public void cancel(JSONObject claim) {
        if (!ready || busy) return;
        new MaterialAlertDialogBuilder(this).setTitle(R.string.ui_cancel_claim)
                .setMessage(R.string.ui_cancel_claim_confirm).setNegativeButton(R.string.ui_cancel, null)
                .setPositiveButton(R.string.ui_confirm_action, (dialog, which) -> {
                    if (!ready || busy) return;
                    beginWrite();
                    api.cancelClaim(claim.optLong("id"), (code, response, error) -> {
                        if (!visible) return;
                        JSONObject data = result(code, response, error, true);
                        if (data != null) { message(R.string.ui_action_success); loadRecords(); }
                        else if (code == 409) {
                            Snackbar.make(findViewById(R.id.business_root), response.optString("message"), Snackbar.LENGTH_LONG).show();
                            loadRecords();
                        }
                    });
                }).show();
    }
    @Override public void confirm(JSONObject claim) { }
    @Override public void reject(JSONObject claim) { }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("claims_mode", claimsMode);
        super.onSaveInstanceState(state);
    }
}
