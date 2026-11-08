package com.example.campuslostfound.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.example.campuslostfound.R;
import org.json.JSONObject;

public class ReceivedClaimsActivity extends BusinessActivity implements ClaimAdapter.Actions {
    private long postId;
    private Spinner status;
    private ClaimAdapter adapter;
    private AlertDialog reasonForm;
    private long reasonDraftClaim;
    private String reasonDraft = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_received_claims);
        bindPage(R.string.ui_received);
        postId = getIntent().getLongExtra("postId", 0);
        if (state != null) {
            reasonDraftClaim = state.getLong("reason_claim");
            reasonDraft = state.getString("reason_draft", "");
        }
        status = findViewById(R.id.received_status);
        UiSupport.options(status, R.array.ui_claim_statuses);
        adapter = new ClaimAdapter(this, true, this);
        ((ListView) findViewById(R.id.received_list)).setAdapter(adapter);
        findViewById(R.id.refresh_claims).setOnClickListener(view -> onReady());
    }

    @Override protected void onReady() {
        if (!ready || busy) return;
        adapter.replace(null, false);
        setBusy(true);
        showState(getString(R.string.ui_loading), false);
        api.getPost(postId, (code, response, error) -> {
            if (!visible) return;
            JSONObject post = result(code, response, error, false);
            if (post == null) return;
            JSONObject owner = post.optJSONObject("owner");
            if (owner == null || owner.optLong("id") != memberId) {
                showState(getString(R.string.ui_forbidden), false); return;
            }
            ((TextView) findViewById(R.id.received_title)).setText(getString(R.string.ui_post_meta,
                    UiSupport.text(post, "title"), getString(UiSupport.stateLabel(post.optString("status")))));
            boolean open = "OPEN".equals(post.optString("status"));
            setBusy(true);
            api.getReceivedClaims(postId, UiSupport.CLAIM_STATUSES[status.getSelectedItemPosition()], (c, res, err) -> {
                if (!visible) return;
                JSONObject data = result(c, res, err, false);
                if (data == null) return;
                clearWriteUncertainty();
                adapter.replace(data.optJSONArray("items"), open);
                showState(getString(adapter.getCount() == 0 ? R.string.ui_empty_received
                        : data.optBoolean("hasMore") ? R.string.ui_more : R.string.ui_records_count, adapter.getCount()), false);
            });
        });
    }

    @Override protected void onBusyChanged() {
        if (status == null) return;
        boolean enabled = ready && !busy;
        status.setEnabled(enabled);
        findViewById(R.id.refresh_claims).setEnabled(enabled);
        adapter.enableActions(enabled && !hasUncertainWrite());
    }

    @Override protected void reviewUncertainWrite() { onReady(); }

    @Override public void openPost(long id) {
        startActivity(new Intent(this, PostDetailActivity.class).putExtra("postId", id));
    }
    @Override public void cancel(JSONObject claim) { }

    @Override public void confirm(JSONObject claim) {
        if (!ready || busy) return;
        new MaterialAlertDialogBuilder(this).setTitle(R.string.ui_confirm_handover)
                .setMessage(R.string.ui_handover_confirm).setNegativeButton(R.string.ui_cancel, null)
                .setPositiveButton(R.string.ui_confirm_action, (dialog, which) -> {
                    if (!ready || busy) return;
                    beginWrite();
                    api.confirmClaim(claim.optLong("id"), this::handleWrite);
                }).show();
    }

    @Override public void reject(JSONObject claim) {
        if (!ready || busy) return;
        View fields = getLayoutInflater().inflate(R.layout.dialog_reason, null);
        EditText reason = fields.findViewById(R.id.claim_reason);
        if (reasonDraftClaim == claim.optLong("id")) reason.setText(reasonDraft);
        reasonForm = new MaterialAlertDialogBuilder(this).setTitle(R.string.ui_reject).setView(fields)
                .setNegativeButton(R.string.ui_cancel, null).setPositiveButton(R.string.ui_reason_submit, null).create();
        reasonForm.setOnShowListener(ignored -> reasonForm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!ready || busy || !UiSupport.validate(reason, 2, 100)) return;
            reasonForm.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            reasonDraftClaim = claim.optLong("id");
            reasonDraft = reason.getText().toString();
            beginWrite();
            api.rejectClaim(claim.optLong("id"), reason.getText().toString().trim(), (code, response, error) -> {
                if (!visible) return;
                reasonForm.dismiss();
                handleWrite(code, response, error);
            });
        }));
        reasonForm.show();
    }

    private void handleWrite(int code, JSONObject response, Exception error) {
        if (!visible) return;
        JSONObject data = result(code, response, error, true);
        if (data != null) { reasonDraft = ""; message(R.string.ui_action_success); onReady(); }
        else if (code == 409) {
            Snackbar.make(findViewById(R.id.business_root), response.optString("message"), Snackbar.LENGTH_LONG).show();
            onReady();
        }
    }

    @Override protected void onStop() {
        if (reasonForm != null) reasonForm.dismiss();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (reasonForm != null && reasonForm.isShowing()) {
            EditText reason = reasonForm.findViewById(R.id.claim_reason);
            if (reason != null) reasonDraft = reason.getText().toString();
        }
        out.putLong("reason_claim", reasonDraftClaim);
        out.putString("reason_draft", reasonDraft);
        super.onSaveInstanceState(out);
    }
}
