package com.example.campuslostfound.ui;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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

/** 第二步临时客户端页面，正式业务界面由前端接入公共 api。 */
public class ConnectActivity extends AppCompatActivity {
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private EditText addressInput;
    private TextView result;
    private Button connectButton;
    private LanApiClient client;
    private LanApiClient.RequestHandle request;
    private boolean visible;
    private boolean busy;

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
        result = findViewById(R.id.connection_result);
        connectButton = findViewById(R.id.connect_host);
        client = new LanApiClient(getApplicationContext());
        if (savedInstanceState == null) {
            addressInput.setText(ClientConnectionStore.getInstance(this).getBaseAddress());
        }
        connectButton.setOnClickListener(view -> requestConnection());
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
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
        result.setText(R.string.client_connecting);
        request = client.connect(addressInput.getText().toString(), new LanApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (!visible) {
                    return;
                }
                setBusy(false);
                addressInput.setText(ClientConnectionStore.getInstance(ConnectActivity.this).getBaseAddress());
                result.setText(getString(R.string.client_connected,
                        data.optString("app"), data.optInt("protocolVersion"), data.optString("hostId")));
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

    private void setBusy(boolean value) {
        busy = value;
        if (connectButton != null) {
            connectButton.setEnabled(!value);
            addressInput.setEnabled(!value);
        }
    }

    @Override
    protected void onStop() {
        visible = false;
        if (request != null) {
            request.cancel();
        }
        result.setText(R.string.client_idle);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        client.close();
        super.onDestroy();
    }
}
