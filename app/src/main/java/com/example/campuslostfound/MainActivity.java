package com.example.campuslostfound;

import android.os.Bundle;
import android.content.Intent;

import com.example.campuslostfound.ui.HostActivity;
import com.example.campuslostfound.ui.ConnectActivity;
import com.example.campuslostfound.ui.ChineseActivity;

import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends ChineseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        findViewById(R.id.open_host).setOnClickListener(view ->
                startActivity(new Intent(this, HostActivity.class)));
        findViewById(R.id.open_client).setOnClickListener(view ->
                startActivity(new Intent(this, ConnectActivity.class)));
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int padding = Math.round(24 * getResources().getDisplayMetrics().density);
            v.setPadding(systemBars.left + padding, systemBars.top + padding,
                    systemBars.right + padding, systemBars.bottom + padding);
            return insets;
        });
    }
}
