package com.example.campuslostfound.ui;

import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.campuslostfound.R;
import com.example.campuslostfound.api.ClientConnectionStore;
import com.example.campuslostfound.api.LanApiClient;

import org.json.JSONObject;

/** A 的连接与身份调试页，后续正式业务界面复用 api。 */
public class ConnectActivity extends AppCompatActivity {
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private EditText addressInput;
    private EditText nicknameInput;
    private TextView result;
    private TextView identityResult;
    private View sessionPanel;
    private View nicknameControls;
    private Button connectButton;
    private Button createButton;
    private Button restoreButton;
    private Button businessButton;
    private LanApiClient client;
    private ClientConnectionStore store;
    private LanApiClient.RequestHandle request;
    private boolean visible;
    private boolean busy;
    private String connectedAddress;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                setBusy(false);
                if (!visible) {
                    return;
                }
                if (granted) {
                    connect();
                } else {
                    result.setText(R.string.client_permission_denied);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_connect);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.connect_root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int padding = Math.round(24 * getResources().getDisplayMetrics().density);
            view.setPadding(bars.left + padding, bars.top + padding,
                    bars.right + padding, bars.bottom + padding);
            return insets;
        });
        addressInput = findViewById(R.id.host_address_input);
        nicknameInput = findViewById(R.id.nickname_input);
        result = findViewById(R.id.connection_result);
        identityResult = findViewById(R.id.identity_result);
        sessionPanel = findViewById(R.id.session_panel);
        nicknameControls = findViewById(R.id.nickname_controls);
        connectButton = findViewById(R.id.connect_host);
        createButton = findViewById(R.id.create_session);
        restoreButton = findViewById(R.id.restore_session);
        businessButton = findViewById(R.id.open_business_debug);
        store = ClientConnectionStore.getInstance(this);
        client = new LanApiClient(getApplicationContext());
        if (savedInstanceState == null) {
            addressInput.setText(store.getBaseAddress());
        }
        addressInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (connectedAddress != null && !connectedAddress.equals(text.toString().trim())) {
                    connectedAddress = null;
                    sessionPanel.setVisibility(View.GONE);
                    result.setText(R.string.client_address_changed);
                }
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        connectButton.setOnClickListener(view -> requestConnection());
        createButton.setOnClickListener(view -> createSession());
        restoreButton.setOnClickListener(view -> restoreSession());
        businessButton.setOnClickListener(view -> {
            if (!busy && connectedAddress != null && store.hasIdentity()) {
                startActivity(new Intent(this, BusinessDebugActivity.class));
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        connectedAddress = null;
        sessionPanel.setVisibility(View.GONE);
        setBusy(false);
    }

    private void requestConnection() {
        if (busy) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 37
                && checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            setBusy(true);
            result.setText(R.string.client_permission_request);
            permissionLauncher.launch(LOCAL_NETWORK_PERMISSION);
        } else {
            connect();
        }
    }

    private void connect() {
        setBusy(true);
        connectedAddress = null;
        sessionPanel.setVisibility(View.GONE);
        result.setText(R.string.client_connecting);
        request = client.connect(addressInput.getText().toString(), new LanApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (!visible) {
                    return;
                }
                setBusy(false);
                addressInput.setText(store.getBaseAddress());
                connectedAddress = store.getBaseAddress();
                result.setText(getString(R.string.client_connected,
                        data.optString("app"), data.optInt("protocolVersion"), data.optString("hostId")));
                sessionPanel.setVisibility(View.VISIBLE);
                if (store.hasIdentity()) {
                    restoreSession();
                } else {
                    showNickname(R.string.session_new_user);
                }
            }

            @Override
            public void onFailure(LanApiClient.ApiError error) {
                if (visible) {
                    setBusy(false);
                    result.setText(getString(R.string.client_failed, error.message));
                }
            }
        });
    }

    private void createSession() {
        if (busy || connectedAddress == null) {
            return;
        }
        String nickname = nicknameInput.getText().toString().trim();
        int count = nickname.codePointCount(0, nickname.length());
        if (count < 1 || count > 20) {
            nicknameInput.setError(getString(R.string.session_nickname_invalid));
            return;
        }
        setBusy(true);
        identityResult.setText(R.string.session_creating);
        request = client.createSession(nickname, identityCallback(true));
    }

    private void restoreSession() {
        if (busy || connectedAddress == null) {
            return;
        }
        setBusy(true);
        nicknameControls.setVisibility(View.GONE);
        identityResult.setText(R.string.session_restoring);
        request = client.restoreSession(identityCallback(false));
    }

    private LanApiClient.Callback identityCallback(boolean created) {
        return new LanApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (!visible) {
                    return;
                }
                setBusy(false);
                JSONObject member = data.optJSONObject("member");
                nicknameControls.setVisibility(View.GONE);
                restoreButton.setVisibility(View.VISIBLE);
                nicknameInput.setText("");
                businessButton.setVisibility(View.VISIBLE);
                identityResult.setText(getString(created ? R.string.session_created : R.string.session_restored,
                        member.optLong("id"), member.optString("nickname")));
            }

            @Override
            public void onFailure(LanApiClient.ApiError error) {
                if (!visible) {
                    return;
                }
                setBusy(false);
                if (error.statusCode == 401) {
                    showNickname(R.string.session_invalid);
                } else {
                    // 网络失败保留已有凭据，避免自动创建第二个用户。
                    if (store.hasIdentity()) {
                        nicknameControls.setVisibility(View.GONE);
                        restoreButton.setVisibility(View.VISIBLE);
                    } else {
                        nicknameControls.setVisibility(View.VISIBLE);
                        restoreButton.setVisibility(View.GONE);
                    }
                    identityResult.setText(getString(R.string.session_failed, error.message));
                }
            }
        };
    }

    private void showNickname(int message) {
        businessButton.setVisibility(View.GONE);
        nicknameControls.setVisibility(View.VISIBLE);
        restoreButton.setVisibility(View.GONE);
        identityResult.setText(message);
    }

    private void setBusy(boolean value) {
        busy = value;
        if (connectButton != null) {
            connectButton.setEnabled(!value);
            addressInput.setEnabled(!value);
            nicknameInput.setEnabled(!value);
            createButton.setEnabled(!value);
            restoreButton.setEnabled(!value);
            businessButton.setEnabled(!value);
        }
    }

    @Override
    protected void onStop() {
        visible = false;
        if (request != null) {
            request.cancel();
        }
        connectedAddress = null;
        sessionPanel.setVisibility(View.GONE);
        result.setText(R.string.client_idle);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        client.close();
        super.onDestroy();
    }
}
