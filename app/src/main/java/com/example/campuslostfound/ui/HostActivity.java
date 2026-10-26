package com.example.campuslostfound.ui;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.campuslostfound.R;
import com.example.campuslostfound.common.LanAddress;
import com.example.campuslostfound.server.ServerManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A 的临时主机调试页，正式业务页面后续由前端实现。 */
public class HostActivity extends AppCompatActivity {
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    // 页面重建也共用一个队列，保证旧页面停止完成后才执行新页面启动。
    // 队列随进程存在；页面退出仍会排队停止监听。
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final ServerManager serverManager = ServerManager.getInstance();
    private TextView status;
    private TextView addresses;
    private Button startButton;
    private Button stopButton;
    private Button refreshButton;
    private boolean visible;
    private boolean busy;
    private int lifecycleGeneration;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!visible) {
                    return;
                }
                if (granted) {
                    busy = false;
                    startServer();
                } else {
                    busy = false;
                    status.setText(R.string.host_permission_denied);
                    updateButtons();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_host);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.host_root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int padding = Math.round(24 * getResources().getDisplayMetrics().density);
            view.setPadding(bars.left + padding, bars.top + padding,
                    bars.right + padding, bars.bottom + padding);
            return insets;
        });
        status = findViewById(R.id.host_status);
        addresses = findViewById(R.id.host_addresses);
        startButton = findViewById(R.id.start_server);
        stopButton = findViewById(R.id.stop_server);
        refreshButton = findViewById(R.id.refresh_addresses);
        startButton.setOnClickListener(view -> requestStart());
        stopButton.setOnClickListener(view -> stopServer());
        refreshButton.setOnClickListener(view -> refreshAddresses());
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        busy = false;
        status.setText(R.string.host_stopped);
        updateButtons();
        refreshAddresses();
    }

    private void requestStart() {
        if (Build.VERSION.SDK_INT >= 37
                && checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            busy = true;
            updateButtons();
            status.setText(R.string.host_permission_request);
            permissionLauncher.launch(LOCAL_NETWORK_PERMISSION);
            return;
        }
        startServer();
    }

    private void startServer() {
        final int generation = lifecycleGeneration;
        busy = true;
        status.setText(R.string.host_starting);
        updateButtons();
        WORKER.execute(() -> {
            String error = null;
            try {
                serverManager.start(getApplicationContext());
            } catch (IOException | RuntimeException e) {
                error = e.getLocalizedMessage();
                if (error == null || error.trim().isEmpty()) {
                    error = getString(R.string.host_unknown_error);
                }
            }
            final String failure = error;
            runOnUiThread(() -> {
                if (generation != lifecycleGeneration) {
                    return;
                }
                busy = false;
                if (!visible) {
                    return;
                }
                if (failure == null) {
                    status.setText(R.string.host_running);
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    status.setText(getString(R.string.host_start_failed, failure));
                }
                updateButtons();
                refreshAddresses();
            });
        });
    }

    private void stopServer() {
        final int generation = lifecycleGeneration;
        busy = true;
        status.setText(R.string.host_stopping);
        updateButtons();
        WORKER.execute(() -> {
            serverManager.stop();
            runOnUiThread(() -> {
                if (generation != lifecycleGeneration) {
                    return;
                }
                busy = false;
                if (visible && generation == lifecycleGeneration) {
                    status.setText(R.string.host_stopped);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    updateButtons();
                }
            });
        });
    }

    private void refreshAddresses() {
        final int generation = lifecycleGeneration;
        refreshButton.setEnabled(false);
        WORKER.execute(() -> {
            List<String> candidates = LanAddress.find(getApplicationContext());
            runOnUiThread(() -> {
                if (visible && generation == lifecycleGeneration) {
                    addresses.setText(candidates.isEmpty() ? getString(R.string.host_no_address)
                            : android.text.TextUtils.join("\n\n", candidates));
                    refreshButton.setEnabled(true);
                    updateButtons();
                }
            });
        });
    }

    private void updateButtons() {
        boolean running = serverManager.isRunning();
        startButton.setEnabled(!busy && !running);
        stopButton.setEnabled(!busy && running);
    }

    @Override
    protected void onStop() {
        visible = false;
        lifecycleGeneration++;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 与启动共用串行队列：即使启动尚未完成，退出后也一定执行停止。
        WORKER.execute(serverManager::stop);
        super.onStop();
    }
}
