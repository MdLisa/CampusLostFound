package com.example.campuslostfound.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.example.campuslostfound.R;
import com.example.campuslostfound.common.ImageCompressor;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PublishPostActivity extends BusinessActivity {
    private Spinner type, category;
    private EditText title, description, location;
    private ImageView photo;
    private TextView photoStatus;
    private final ExecutorService imageWorker = Executors.newSingleThreadExecutor();
    private ImageCompressor.CompressedImage selected;
    private Bitmap preview;
    private Uri pendingUri;
    private int imageGeneration;
    private boolean imageBusy;
    private boolean unknownOutcome;
    private boolean recreatedPhoto;

    private final ActivityResultLauncher<String> picker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pendingUri = uri;
                    if (visible && ready) preparePhoto();
                }
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_publish_post);
        bindPage(R.string.ui_publish);
        type = findViewById(R.id.publish_type);
        category = findViewById(R.id.publish_category);
        title = findViewById(R.id.publish_title);
        description = findViewById(R.id.publish_description);
        location = findViewById(R.id.publish_location);
        photo = findViewById(R.id.publish_photo);
        photoStatus = findViewById(R.id.publish_photo_status);
        UiSupport.options(type, R.array.ui_publish_types);
        UiSupport.options(category, R.array.ui_publish_categories);
        unknownOutcome = state != null && state.getBoolean("publish_unknown");
        recreatedPhoto = state != null && state.getBoolean("had_photo");
        findViewById(R.id.choose_photo).setOnClickListener(view -> {
            if (ready && !busy && !imageBusy) picker.launch("image/*");
        });
        findViewById(R.id.remove_photo).setOnClickListener(view -> {
            clearPhoto(); renderPhoto(); onBusyChanged();
        });
        findViewById(R.id.submit_post).setOnClickListener(view -> publish());
        findViewById(R.id.check_publish_records).setOnClickListener(view ->
                openRecordsForVerification(false));
    }

    @Override protected void onReady() {
        showState(unknownOutcome ? getString(R.string.ui_unknown_write) : "", false);
        findViewById(R.id.check_publish_records).setVisibility(unknownOutcome ? View.VISIBLE : View.GONE);
        renderPhoto();
        preparePhoto();
        onBusyChanged();
    }

    private void publish() {
        if (!ready || busy || imageBusy || unknownOutcome) return;
        if (type.getSelectedItemPosition() == 0 || category.getSelectedItemPosition() == 0) {
            showState(getString(R.string.ui_choose_required), false); return;
        }
        if (!UiSupport.validate(title, 2, 40) || !UiSupport.validate(description, 5, 500)
                || !UiSupport.validate(location, 2, 60)) return;
        JSONObject body = new JSONObject();
        try {
            body.put("type", UiSupport.TYPES[type.getSelectedItemPosition()])
                    .put("title", title.getText().toString().trim())
                    .put("description", description.getText().toString().trim())
                    .put("category", UiSupport.CATEGORIES[category.getSelectedItemPosition()])
                    .put("location", location.getText().toString().trim());
            if (selected != null) body.put("imageBase64", selected.base64);
        } catch (JSONException impossible) { return; }
        // close/cancel 不能证明写入已回滚；在成功或明确业务失败前保留未知标记。
        unknownOutcome = true;
        beginWrite();
        showState(getString(R.string.ui_busy), false);
        api.publishPost(body, (code, response, error) -> {
            if (!visible) return;
            JSONObject data = result(code, response, error, true);
            if (code > 0) unknownOutcome = false;
            if (data != null) {
                startActivity(new Intent(this, PostDetailActivity.class).putExtra("postId", data.optLong("id")));
                finish();
            } else {
                findViewById(R.id.check_publish_records).setVisibility(unknownOutcome ? View.VISIBLE : View.GONE);
                onBusyChanged();
            }
        });
    }

    private void preparePhoto() {
        if (pendingUri == null || imageBusy || !ready || !visible || imageWorker.isShutdown()) return;
        Uri uri = pendingUri;
        pendingUri = null;
        clearPhoto();
        imageBusy = true;
        recreatedPhoto = false;
        int round = ++imageGeneration;
        renderPhoto();
        onBusyChanged();
        imageWorker.execute(() -> {
            ImageCompressor.CompressedImage compressed = null;
            Bitmap bitmap = null;
            try {
                compressed = ImageCompressor.compress(getApplicationContext(), uri);
                bitmap = ImageCompressor.decodeJpeg(compressed.jpeg);
            } catch (Exception failure) {
                // 不把本机路径、异常堆栈展示在用户界面。
            }
            final ImageCompressor.CompressedImage imageResult = compressed;
            final Bitmap bitmapResult = bitmap;
            runOnUiThread(() -> {
                if (isDestroyed() || round != imageGeneration) {
                    if (bitmapResult != null) bitmapResult.recycle();
                    return;
                }
                imageBusy = false;
                selected = bitmapResult == null ? null : imageResult;
                preview = bitmapResult;
                if (visible) {
                    renderPhoto();
                    if (selected == null) photoStatus.setText(R.string.photo_prepare_error);
                    onBusyChanged();
                }
            });
        });
    }

    private void renderPhoto() {
        photo.setImageBitmap(preview);
        photo.setVisibility(preview == null ? View.GONE : View.VISIBLE);
        findViewById(R.id.remove_photo).setVisibility(selected == null ? View.GONE : View.VISIBLE);
        if (imageBusy) photoStatus.setText(R.string.photo_preparing);
        else if (selected != null) photoStatus.setText(getString(R.string.photo_prepared,
                selected.width, selected.height, (selected.jpeg.length + 1023) / 1024));
        else photoStatus.setText(recreatedPhoto ? R.string.ui_image_lost : R.string.photo_none_selected);
    }

    private void clearPhoto() {
        imageGeneration++;
        imageBusy = false;
        selected = null;
        photo.setImageDrawable(null);
        preview = null;
    }

    @Override protected void onBusyChanged() {
        if (title == null) return;
        boolean enabled = ready && !busy && !imageBusy;
        for (int id : new int[]{R.id.publish_type, R.id.publish_category, R.id.publish_title,
                R.id.publish_description, R.id.publish_location, R.id.choose_photo, R.id.remove_photo}) {
            findViewById(id).setEnabled(enabled);
        }
        findViewById(R.id.submit_post).setEnabled(enabled && !unknownOutcome);
    }

    @Override protected void onWriteOutcomeVerified() {
        unknownOutcome = false;
        if (title != null) {
            findViewById(R.id.check_publish_records).setVisibility(View.GONE);
            showState("", false);
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("publish_unknown", unknownOutcome);
        out.putBoolean("had_photo", selected != null || pendingUri != null || imageBusy || recreatedPhoto);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        clearPhoto();
        imageWorker.shutdownNow();
        super.onDestroy();
    }
}
