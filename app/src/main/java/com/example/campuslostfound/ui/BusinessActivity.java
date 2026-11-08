package com.example.campuslostfound.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;
import com.example.campuslostfound.R;
import com.example.campuslostfound.api.ClientConnectionStore;
import com.example.campuslostfound.api.LanApiClient;
import com.example.campuslostfound.api.LanApiTransport;
import com.example.campuslostfound.api.LostFoundApi;
import org.json.JSONObject;

/** 五个正式页面共用的身份与页面生命周期，不持有静态网络对象。 */
public abstract class BusinessActivity extends ChineseActivity {
    protected LanApiClient client;
    protected LostFoundApi api;
    protected boolean visible;
    protected boolean ready;
    protected boolean busy;
    protected long memberId;
    protected String nickname;
    private boolean writePending;
    private boolean writeUnknown;
    private int generation;
    private Snackbar recoveryNotice;
    private final ActivityResultLauncher<Intent> recordsCheck = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) clearWriteUncertainty();
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        writeUnknown = state != null && state.getBoolean("ui_write_unknown");
    }

    protected void bindPage(int title) {
        UiSupport.insets(this, R.id.business_root);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(title);
        toolbar.setNavigationOnClickListener(view -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.page_retry).setOnClickListener(view -> {
            if (!busy && visible) {
                if (!ready) restoreIdentity();
                else if (writeUnknown) reviewUncertainWrite();
                else onReady();
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        ready = false;
        client = new LanApiClient(getApplicationContext());
        api = new LostFoundApi(new LanApiTransport(client));
        findViewById(R.id.business_body).setVisibility(View.GONE);
        restoreIdentity();
    }

    private void restoreIdentity() {
        setBusy(true);
        showState(getString(R.string.ui_restoring), false);
        final int round = generation;
        client.restoreSession(new LanApiClient.Callback() {
            @Override public void onSuccess(JSONObject data) {
                if (!visible || round != generation) return;
                JSONObject member = data.optJSONObject("member");
                if (member == null || member.optLong("id") <= 0) { reconnect(); return; }
                memberId = member.optLong("id");
                nickname = UiSupport.text(member, "nickname");
                ready = true;
                setBusy(false);
                findViewById(R.id.business_body).setVisibility(View.VISIBLE);
                if (writeUnknown) {
                    showWriteRecovery();
                }
                onReady();
            }
            @Override public void onFailure(LanApiClient.ApiError error) {
                if (!visible || round != generation) return;
                setBusy(false);
                if (error.statusCode == 401 || "请先检测并连接主机".equals(error.message)
                        || !ClientConnectionStore.getInstance(BusinessActivity.this).hasIdentity()) {
                    reconnect();
                } else showState(getString(R.string.ui_load_failed,
                        UiSupport.errorMessage(BusinessActivity.this, error.message,
                                R.string.ui_network_failed)), true);
            }
        });
    }

    protected abstract void onReady();
    protected void onBusyChanged() { }
    protected void onWriteOutcomeVerified() { }

    protected final boolean hasUncertainWrite() { return writeUnknown; }

    protected void reviewUncertainWrite() { openRecordsForVerification(false); }

    protected final void openRecordsForVerification(boolean claims) {
        recordsCheck.launch(new Intent(this, MyRecordsActivity.class)
                .putExtra("claims", claims).putExtra("verifyWriteOutcome", true));
    }

    protected final void clearWriteUncertainty() {
        writeUnknown = false;
        if (recoveryNotice != null) recoveryNotice.dismiss();
        onWriteOutcomeVerified();
        onBusyChanged();
    }

    private void showWriteRecovery() {
        if (recoveryNotice != null) recoveryNotice.dismiss();
        recoveryNotice = Snackbar.make(findViewById(R.id.business_root), R.string.ui_unknown_write,
                Snackbar.LENGTH_INDEFINITE).setAction(R.string.ui_view_records, view -> reviewUncertainWrite());
        recoveryNotice.show();
    }

    protected final void setBusy(boolean value) {
        busy = value;
        findViewById(R.id.page_progress).setVisibility(value ? View.VISIBLE : View.GONE);
        findViewById(R.id.page_retry).setEnabled(!value);
        onBusyChanged();
    }

    protected final void showState(String message, boolean retry) {
        ((TextView) findViewById(R.id.page_status)).setText(message);
        findViewById(R.id.page_status).setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.page_retry).setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    protected final void message(int text) {
        Snackbar.make(findViewById(R.id.business_root), text, Snackbar.LENGTH_LONG).show();
    }

    protected final void beginWrite() {
        writePending = true;
        writeUnknown = true;
        setBusy(true);
    }

    protected final JSONObject result(int status, JSONObject response, Exception error, boolean write) {
        if (write) writePending = false;
        if (write && error == null && status > 0) writeUnknown = false;
        setBusy(false);
        if (error != null || status == 0) {
            showState(getString(write ? R.string.ui_unknown_write : R.string.ui_network_failed), true);
            if (write) showWriteRecovery();
            return null;
        }
        if (status == 401) { reconnect(); return null; }
        if (status != 200 || response == null) {
            String reason = response == null ? getString(R.string.ui_network_failed)
                    : response.optString("message", getString(R.string.ui_network_failed));
            showState(UiSupport.errorMessage(this, reason, R.string.ui_request_failed), true);
            return null;
        }
        showState("", false);
        return response.optJSONObject("data");
    }

    private void reconnect() {
        ready = false;
        startActivity(new Intent(this, ConnectActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("ui_write_unknown", writeUnknown || writePending);
        super.onSaveInstanceState(out);
    }

    @Override protected void onStop() {
        visible = false;
        ready = false;
        generation++;
        if (writePending) writeUnknown = true;
        writePending = false;
        client.close();
        setBusy(false);
        super.onStop();
    }
}
