package com.example.campuslostfound.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.example.campuslostfound.R;
import com.example.campuslostfound.api.LanApiClient;
import org.json.JSONException;
import org.json.JSONObject;

public class PostDetailActivity extends BusinessActivity {
    private long postId;
    private JSONObject post;
    private ImageView image;
    private String imagePath;
    private AlertDialog form;
    private boolean claimWrite;
    private String proofDraft = "", contactDraft = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_post_detail);
        bindPage(R.string.ui_post_detail);
        postId = getIntent().getLongExtra("postId", 0);
        if (state != null) {
            claimWrite = state.getBoolean("claim_write");
            proofDraft = state.getString("proof_draft", "");
            contactDraft = state.getString("contact_draft", "");
        }
        image = findViewById(R.id.detail_photo);
        findViewById(R.id.apply_claim).setOnClickListener(view -> showClaim());
        findViewById(R.id.received_claims).setOnClickListener(view ->
                startActivity(new Intent(this, ReceivedClaimsActivity.class).putExtra("postId", postId)));
        findViewById(R.id.resolve_post).setOnClickListener(view -> confirmPost(false));
        findViewById(R.id.close_post).setOnClickListener(view -> confirmPost(true));
        findViewById(R.id.retry_photo).setOnClickListener(view -> loadImage());
    }

    @Override protected void onReady() {
        findViewById(R.id.post_actions).setVisibility(View.GONE);
        image.setImageDrawable(null);
        post = null;
        setBusy(true);
        showState(getString(R.string.ui_loading), false);
        api.getPost(postId, (code, response, error) -> {
            if (!visible) return;
            JSONObject data = result(code, response, error, false);
            if (data == null) {
                if (code == 404) showState(getString(R.string.ui_not_found), false);
                return;
            }
            post = data;
            if (!claimWrite) clearWriteUncertainty();
            render();
        });
    }

    private void render() {
        ((TextView) findViewById(R.id.detail_title)).setText(UiSupport.text(post, "title"));
        ((TextView) findViewById(R.id.detail_type)).setText(UiSupport.typeLabel(post.optString("type")));
        ((TextView) findViewById(R.id.detail_state)).setText(UiSupport.stateLabel(post.optString("status")));
        ((TextView) findViewById(R.id.detail_description)).setText(UiSupport.text(post, "description"));
        ((TextView) findViewById(R.id.detail_location)).setText(getString(R.string.ui_post_meta,
                UiSupport.text(post, "location"), UiSupport.category(this, post.optString("category"))));
        JSONObject owner = post.optJSONObject("owner");
        ((TextView) findViewById(R.id.detail_owner)).setText(getString(R.string.ui_owner, UiSupport.text(owner, "nickname")));
        ((TextView) findViewById(R.id.detail_time)).setText(getString(R.string.ui_created, UiSupport.date(post.optLong("createdAt"))));
        boolean mine = owner != null && owner.optLong("id") == memberId;
        boolean open = "OPEN".equals(post.optString("status"));
        findViewById(R.id.post_actions).setVisibility(View.VISIBLE);
        findViewById(R.id.apply_claim).setVisibility(!mine && open ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.apply_claim)).setText("LOST".equals(post.optString("type")) ? R.string.ui_offer : R.string.ui_apply);
        findViewById(R.id.received_claims).setVisibility(mine ? View.VISIBLE : View.GONE);
        findViewById(R.id.resolve_post).setVisibility(mine && open ? View.VISIBLE : View.GONE);
        findViewById(R.id.close_post).setVisibility(mine && open ? View.VISIBLE : View.GONE);
        findViewById(R.id.detail_ended).setVisibility(open ? View.GONE : View.VISIBLE);
        imagePath = UiSupport.text(post, "imagePath");
        loadImage();
    }

    private void loadImage() {
        if (!ready || !visible) return;
        image.setImageDrawable(null);
        image.setVisibility(View.GONE);
        findViewById(R.id.retry_photo).setVisibility(View.GONE);
        TextView photoStatus = findViewById(R.id.detail_photo_status);
        if (imagePath == null || imagePath.isEmpty()) { photoStatus.setText(R.string.ui_no_photo); return; }
        photoStatus.setText(R.string.photo_loading);
        client.loadImage(imagePath, new LanApiClient.ImageCallback() {
            @Override public void onSuccess(Bitmap bitmap) {
                if (!visible) { bitmap.recycle(); return; }
                image.setImageBitmap(bitmap);
                image.setVisibility(View.VISIBLE);
                photoStatus.setText("");
            }
            @Override public void onFailure(LanApiClient.ApiError error) {
                if (!visible) return;
                photoStatus.setText(R.string.ui_image_failed);
                findViewById(R.id.retry_photo).setVisibility(View.VISIBLE);
            }
        });
    }

    private void showClaim() {
        if (!ready || busy || post == null) return;
        if (hasUncertainWrite()) { reviewUncertainWrite(); return; }
        View fields = getLayoutInflater().inflate(R.layout.dialog_claim, null);
        EditText proof = fields.findViewById(R.id.claim_proof);
        EditText contact = fields.findViewById(R.id.claim_contact);
        proof.setText(proofDraft);
        contact.setText(contactDraft);
        boolean lost = "LOST".equals(post.optString("type"));
        ((TextView) fields.findViewById(R.id.claim_proof_label)).setText(lost ? R.string.ui_clue_label : R.string.ui_proof_label);
        proof.setHint(lost ? R.string.ui_clue_hint : R.string.ui_proof_hint);
        form = new MaterialAlertDialogBuilder(this).setTitle(lost ? R.string.ui_offer : R.string.ui_apply)
                .setView(fields).setNegativeButton(R.string.ui_cancel, null)
                .setPositiveButton(R.string.ui_submit, null).create();
        form.setOnShowListener(ignored -> form.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!ready || busy || !UiSupport.validate(proof, 10, 300) || !UiSupport.validate(contact, 3, 80)) return;
            JSONObject body = new JSONObject();
            try { body.put("proof", proof.getText().toString().trim()).put("contact", contact.getText().toString().trim()); }
            catch (JSONException impossible) { return; }
            form.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            proofDraft = proof.getText().toString();
            contactDraft = contact.getText().toString();
            claimWrite = true;
            beginWrite();
            api.submitClaim(postId, body, (code, response, error) -> {
                if (!visible) return;
                if (error != null || code == 0) form.dismiss();
                JSONObject data = result(code, response, error, true);
                if (data != null) {
                    proofDraft = ""; contactDraft = "";
                    form.dismiss();
                    Snackbar.make(findViewById(R.id.business_root), R.string.ui_apply_success, Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.ui_view_claims, v -> startActivity(new Intent(this, MyRecordsActivity.class)
                                    .putExtra("claims", true))).show();
                } else {
                    if (form != null && form.isShowing()) form.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(code > 0);
                    if (code == 409) {
                        form.dismiss();
                        Snackbar.make(findViewById(R.id.business_root), response.optString("message"), Snackbar.LENGTH_LONG).show();
                        onReady();
                    }
                }
            });
        }));
        form.show();
    }

    private void confirmPost(boolean close) {
        if (!ready || busy || post == null) return;
        if (hasUncertainWrite()) { reviewUncertainWrite(); return; }
        new MaterialAlertDialogBuilder(this).setTitle(close ? R.string.ui_close : R.string.ui_resolve)
                .setMessage(close ? R.string.ui_close_confirm : R.string.ui_resolve_confirm)
                .setNegativeButton(R.string.ui_cancel, null).setPositiveButton(R.string.ui_confirm_action, (dialog, which) -> {
                    if (!ready || busy) return;
                    claimWrite = false;
                    beginWrite();
                    com.example.campuslostfound.api.LostFoundApi.Callback cb = (code, response, error) -> {
                        if (!visible) return;
                        JSONObject data = result(code, response, error, true);
                        if (data != null) { message(R.string.ui_action_success); onReady(); }
                        else if (code == 409) {
                            Snackbar.make(findViewById(R.id.business_root), response.optString("message"), Snackbar.LENGTH_LONG).show();
                            onReady();
                        }
                    };
                    if (close) api.closePost(postId, cb); else api.resolvePost(postId, cb);
                }).show();
    }

    @Override protected void onBusyChanged() {
        for (int id : new int[]{R.id.apply_claim, R.id.received_claims, R.id.resolve_post, R.id.close_post}) {
            View view = findViewById(id);
            if (view != null) view.setEnabled(ready && !busy
                    && (id == R.id.received_claims || !hasUncertainWrite()));
        }
    }

    @Override protected void reviewUncertainWrite() { openRecordsForVerification(claimWrite); }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (form != null && form.isShowing()) {
            EditText proof = form.findViewById(R.id.claim_proof);
            EditText contact = form.findViewById(R.id.claim_contact);
            if (proof != null) proofDraft = proof.getText().toString();
            if (contact != null) contactDraft = contact.getText().toString();
        }
        out.putBoolean("claim_write", claimWrite);
        out.putString("proof_draft", proofDraft);
        out.putString("contact_draft", contactDraft);
        super.onSaveInstanceState(out);
    }

    @Override protected void onStop() {
        if (form != null) form.dismiss();
        image.setImageDrawable(null);
        super.onStop();
    }
}
