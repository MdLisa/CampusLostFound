package com.example.campuslostfound.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.campuslostfound.R;
import com.example.campuslostfound.common.ImageCompressor;
import com.example.campuslostfound.api.LanApiClient;
import com.example.campuslostfound.api.LanApiTransport;
import com.example.campuslostfound.api.LostFoundApi;

import org.json.JSONException;
import org.json.JSONObject;

/** 第四步临时联调页；通过 LostFoundApi 调用业务，不直接访问主机数据库。 */
public class BusinessDebugActivity extends AppCompatActivity {
    private static final String[] TYPES = {null, "FOUND", "LOST"};
    private static final String[] CATEGORIES = {null, "CARD", "ELECTRONICS", "KEYS", "BOOKS", "DAILY", "OTHER"};
    private Spinner operation;
    private Spinner type;
    private Spinner category;
    private Spinner status;
    private EditText idInput;
    private EditText title;
    private EditText description;
    private EditText location;
    private EditText keyword;
    private EditText proof;
    private EditText contact;
    private EditText reason;
    private TextView identity;
    private TextView output;
    private TextView idLabel;
    private Button send;
    private LanApiClient client;
    private LostFoundApi api;
    private boolean visible;
    private boolean ready;
    private String[] statusValues = {null};
    private final java.util.concurrent.ExecutorService imageWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private ImageCompressor.CompressedImage selectedImage;
    private android.graphics.Bitmap selectedPreview;
    private android.graphics.Bitmap remotePreview;
    private android.net.Uri pendingPhoto;
    private android.widget.ImageView selectedPhotoView;
    private android.widget.ImageView remotePhotoView;
    private TextView selectedPhotoStatus;
    private TextView remotePhotoStatus;
    private Button choosePhoto;
    private Button removePhoto;
    private LanApiClient.RequestHandle photoRequest;
    private boolean imageBusy;
    private boolean requestBusy;
    // 写请求中断只表示未取得结果，不代表主机已经回滚。
    private boolean writeOutcomeUnknown;
    private int imageGeneration;

    private final androidx.activity.result.ActivityResultLauncher<String> photoPicker =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri != null) {
                            pendingPhoto = uri;
                            if (visible) preparePendingPhoto();
                        }
                    });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_business_debug);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.business_root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int padding = Math.round(20 * getResources().getDisplayMetrics().density);
            view.setPadding(bars.left + padding, bars.top + padding,
                    bars.right + padding, bars.bottom + padding);
            return insets;
        });
        operation = findViewById(R.id.debug_operation);
        type = findViewById(R.id.debug_type);
        category = findViewById(R.id.debug_category);
        status = findViewById(R.id.debug_status);
        idInput = findViewById(R.id.debug_id);
        title = findViewById(R.id.debug_title);
        description = findViewById(R.id.debug_description);
        location = findViewById(R.id.debug_location);
        keyword = findViewById(R.id.debug_keyword);
        proof = findViewById(R.id.debug_proof);
        contact = findViewById(R.id.debug_contact);
        reason = findViewById(R.id.debug_reason);
        identity = findViewById(R.id.debug_identity);
        output = findViewById(R.id.debug_output);
        writeOutcomeUnknown = savedInstanceState != null
                && savedInstanceState.getBoolean("write_outcome_unknown", false);
        idLabel = findViewById(R.id.debug_id_label);
        send = findViewById(R.id.debug_send);
        choosePhoto = findViewById(R.id.debug_choose_photo);
        removePhoto = findViewById(R.id.debug_remove_photo);
        selectedPhotoView = findViewById(R.id.debug_selected_photo);
        remotePhotoView = findViewById(R.id.debug_remote_photo);
        selectedPhotoStatus = findViewById(R.id.debug_selected_photo_status);
        remotePhotoStatus = findViewById(R.id.debug_remote_photo_status);
        choosePhoto.setOnClickListener(view -> photoPicker.launch("image/*"));
        removePhoto.setOnClickListener(view -> {
            clearSelectedPhoto();
            setBusy(requestBusy);
        });
        setOptions(type, R.array.debug_types);
        setOptions(category, R.array.debug_categories);
        setOptions(operation, R.array.debug_operations);
        type.setSelection(1);
        category.setSelection(3);
        operation.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                configureFields(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        configureFields(0);
        send.setOnClickListener(view -> sendRequest());
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        ready = false;
        output.setText(writeOutcomeUnknown ? R.string.debug_write_unknown : R.string.debug_ready);
        setBusy(true);
        client = new LanApiClient(getApplicationContext());
        api = new LostFoundApi(new LanApiTransport(client));
        identity.setText(R.string.session_restoring);
        client.restoreSession(new LanApiClient.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (!visible) return;
                JSONObject member = data.optJSONObject("member");
                identity.setText(getString(R.string.debug_identity_value,
                        member.optLong("id"), member.optString("nickname")));
                ready = true;
                setBusy(false);
            }

            @Override
            public void onFailure(LanApiClient.ApiError error) {
                if (!visible) return;
                ready = false;
                setBusy(false);
                identity.setText(getString(R.string.debug_identity_failed, error.message));
            }
        });
        preparePendingPhoto();
    }

    private void setOptions(Spinner spinner, int array) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void configureFields(int selected) {
        show(R.id.debug_publish_fields, selected == 0);
        show(R.id.debug_image_selection, selected == 0);
        show(R.id.debug_filters, selected == 0 || selected == 1);
        show(R.id.debug_keyword, selected == 1);
        show(R.id.debug_id_group, selected == 2 || selected == 4 || selected == 6 || selected >= 7);
        show(R.id.debug_claim_fields, selected == 4);
        show(R.id.debug_reason, selected == 8);
        show(R.id.debug_status_group, selected == 1 || selected == 3 || selected == 5 || selected == 6);
        idLabel.setText(selected == 7 || selected == 8 || selected == 9
                ? R.string.debug_claim_id : R.string.debug_post_id);
        if (selected == 5 || selected == 6) {
            setOptions(status, R.array.debug_claim_statuses);
            statusValues = new String[]{null, "PENDING", "ACCEPTED", "REJECTED", "CANCELLED"};
        } else if (selected == 3) {
            setOptions(status, R.array.debug_my_post_statuses);
            statusValues = new String[]{null, "OPEN", "RESOLVED", "CLOSED"};
        } else {
            setOptions(status, R.array.debug_public_statuses);
            statusValues = new String[]{null, "OPEN", "RESOLVED"};
        }
    }

    private void show(int id, boolean show) {
        findViewById(id).setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private String text(EditText input) {
        return input.getText().toString().trim();
    }

    private void sendRequest() {
        if (!ready || requestBusy || imageBusy) return;
        int selected = operation.getSelectedItemPosition();
        long id = 0;
        if (selected == 2 || selected == 4 || selected == 6 || selected >= 7) {
            try {
                id = Long.parseLong(text(idInput));
                if (id <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                idInput.setError(getString(R.string.debug_id_invalid));
                return;
            }
        }
        final boolean writing = selected == 0 || selected == 4 || selected >= 7;
        writeOutcomeUnknown = writing;
        setBusy(true);
        clearRemotePhoto();
        output.setText(R.string.debug_sending);
        LostFoundApi.Callback callback = (code, response, error) -> {
            if (!visible) return;
            writeOutcomeUnknown = writing && error != null;
            if (code == 401) {
                ready = false;
                identity.setText(getString(R.string.debug_identity_failed, getString(R.string.session_invalid)));
            }
            setBusy(false);
            try {
                output.setText(error != null
                        ? getString(R.string.debug_network_error, error.getMessage())
                                + (writing ? "\n\n" + getString(R.string.debug_write_unknown) : "")
                        : getString(R.string.debug_response, code, response.toString(2)));
                if (error == null && code == 200 && (selected == 0 || selected == 2)) {
                    loadPostPhoto(response.optJSONObject("data"));
                }
            } catch (JSONException e) {
                output.setText(R.string.debug_display_error);
            }
        };
        try {
            String filter = statusValues[status.getSelectedItemPosition()];
            switch (selected) {
                case 0:
                    if (type.getSelectedItemPosition() == 0 || category.getSelectedItemPosition() == 0) {
                        setBusy(false);
                        writeOutcomeUnknown = false;
                        output.setText(R.string.debug_publish_filter_required);
                        return;
                    }
                    JSONObject post = new JSONObject().put("type", TYPES[type.getSelectedItemPosition()])
                            .put("category", CATEGORIES[category.getSelectedItemPosition()])
                            .put("title", text(title)).put("description", text(description))
                            .put("location", text(location));
                    if (selectedImage != null) post.put("imageBase64", selectedImage.base64);
                    api.publishPost(post, callback);
                    break;
                case 1: api.listPosts(TYPES[type.getSelectedItemPosition()],
                        CATEGORIES[category.getSelectedItemPosition()], text(keyword), filter, callback); break;
                case 2: api.getPost(id, callback); break;
                case 3: api.getMyPosts(filter, callback); break;
                case 4: api.submitClaim(id, new JSONObject().put("proof", text(proof))
                        .put("contact", text(contact)), callback); break;
                case 5: api.getMyClaims(filter, callback); break;
                case 6: api.getReceivedClaims(id, filter, callback); break;
                case 7: api.confirmClaim(id, callback); break;
                case 8: api.rejectClaim(id, text(reason), callback); break;
                case 9: api.cancelClaim(id, callback); break;
                case 10: api.resolvePost(id, callback); break;
                case 11: api.closePost(id, callback); break;
                default: setBusy(false);
            }
        } catch (JSONException e) {
            writeOutcomeUnknown = false;
            setBusy(false);
            output.setText(R.string.debug_display_error);
        }
    }

    private void setBusy(boolean busy) {
        requestBusy = busy;
        busy = busy || imageBusy;
        choosePhoto.setEnabled(!busy && ready);
        removePhoto.setEnabled(!busy && selectedImage != null);
        send.setEnabled(!busy && ready);
        for (int id : new int[]{R.id.debug_operation, R.id.debug_type, R.id.debug_category,
                R.id.debug_status, R.id.debug_id, R.id.debug_title, R.id.debug_description,
                R.id.debug_location, R.id.debug_keyword, R.id.debug_proof,
                R.id.debug_contact, R.id.debug_reason}) {
            findViewById(id).setEnabled(!busy);
        }
    }

    private void preparePendingPhoto() {
        if (pendingPhoto == null || isDestroyed() || imageWorker.isShutdown()) return;
        android.net.Uri uri = pendingPhoto;
        pendingPhoto = null;
        clearSelectedPhoto();
        final int generation = ++imageGeneration;
        imageBusy = true;
        selectedPhotoStatus.setText(R.string.photo_preparing);
        setBusy(requestBusy);
        imageWorker.execute(() -> {
            ImageCompressor.CompressedImage image = null;
            android.graphics.Bitmap preview = null;
            String failure = null;
            try {
                image = ImageCompressor.compress(getApplicationContext(), uri);
                preview = ImageCompressor.decodeJpeg(image.jpeg);
            } catch (java.io.IOException e) {
                failure = e.getMessage();
            } catch (SecurityException e) {
                failure = getString(R.string.photo_permission_error);
            } catch (RuntimeException e) {
                failure = getString(R.string.photo_prepare_error);
            }
            final ImageCompressor.CompressedImage prepared = image;
            final android.graphics.Bitmap preparedPreview = preview;
            final String message = failure;
            runOnUiThread(() -> {
                if (generation != imageGeneration || isDestroyed()) {
                    if (preparedPreview != null) preparedPreview.recycle();
                    return;
                }
                imageBusy = false;
                if (message == null && prepared != null) {
                    selectedImage = prepared;
                    selectedPreview = preparedPreview;
                    selectedPhotoView.setImageBitmap(preparedPreview);
                    selectedPhotoView.setVisibility(View.VISIBLE);
                    selectedPhotoStatus.setText(getString(R.string.photo_prepared,
                            prepared.width, prepared.height, (prepared.jpeg.length + 1023) / 1024));
                } else {
                    if (preparedPreview != null) preparedPreview.recycle();
                    selectedPhotoStatus.setText(getString(R.string.photo_failed,
                            message == null ? getString(R.string.photo_prepare_error) : message));
                }
                setBusy(requestBusy);
            });
        });
    }

    private void clearSelectedPhoto() {
        imageGeneration++;
        imageBusy = false;
        selectedImage = null;
        selectedPhotoView.setImageDrawable(null);
        selectedPhotoView.setVisibility(View.GONE);
        // 已显示的 Bitmap 由 View/渲染线程共享，移除引用后交给系统回收。
        selectedPreview = null;
        selectedPhotoStatus.setText(R.string.photo_none_selected);
    }

    private void clearRemotePhoto() {
        if (photoRequest != null) photoRequest.cancel();
        photoRequest = null;
        remotePhotoView.setImageDrawable(null);
        remotePhotoView.setVisibility(View.GONE);
        remotePreview = null;
        remotePhotoStatus.setText("");
    }

    private void loadPostPhoto(JSONObject data) {
        clearRemotePhoto();
        if (data == null || data.isNull("imagePath")) {
            remotePhotoStatus.setText(R.string.photo_post_none);
            return;
        }
        setBusy(true);
        remotePhotoStatus.setText(R.string.photo_loading);
        photoRequest = client.loadImage(data.optString("imagePath"), new LanApiClient.ImageCallback() {
            @Override
            public void onSuccess(android.graphics.Bitmap bitmap) {
                if (!visible) {
                    bitmap.recycle();
                    return;
                }
                remotePreview = bitmap;
                remotePhotoView.setImageBitmap(bitmap);
                remotePhotoView.setVisibility(View.VISIBLE);
                remotePhotoStatus.setText(R.string.photo_loaded);
                setBusy(false);
            }

            @Override
            public void onFailure(LanApiClient.ApiError error) {
                if (!visible) return;
                remotePhotoStatus.setText(getString(R.string.photo_failed, error.message));
                setBusy(false);
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("write_outcome_unknown", writeOutcomeUnknown);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        imageGeneration++;
        imageWorker.shutdownNow();
        clearSelectedPhoto();
        clearRemotePhoto();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        visible = false;
        ready = false;
        client.close();
        clearRemotePhoto();
        super.onStop();
    }
}
