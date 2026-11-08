package com.example.campuslostfound.ui;

import android.app.Activity;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import androidx.activity.EdgeToEdge;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import com.example.campuslostfound.R;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** 页面展示与控件绑定；枚举仅映射现有接口的值，不引入业务规则。 */
final class UiSupport {
    static final String[] TYPES = {null, "LOST", "FOUND"};
    static final String[] CATEGORIES = {null, "CARD", "ELECTRONICS", "KEYS", "BOOKS", "DAILY", "OTHER"};
    static final String[] PUBLIC_STATUSES = {null, "OPEN", "RESOLVED"};
    static final String[] POST_STATUSES = {null, "OPEN", "RESOLVED", "CLOSED"};
    static final String[] CLAIM_STATUSES = {null, "PENDING", "ACCEPTED", "REJECTED", "CANCELLED"};

    private UiSupport() { }

    static void insets(Activity activity, int rootId) {
        EdgeToEdge.enable((androidx.activity.ComponentActivity) activity);
        ViewCompat.setOnApplyWindowInsetsListener(activity.findViewById(rootId), (view, window) -> {
            Insets bars = window.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = window.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return window;
        });
    }

    static void options(Spinner spinner, int array) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(spinner.getContext(),
                array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    static String text(JSONObject data, String field) {
        return data == null || data.isNull(field) ? "" : data.optString(field, "");
    }

    static String date(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
                Locale.SIMPLIFIED_CHINESE).format(new Date(timestamp));
    }

    static String errorMessage(Context context, String message, int fallback) {
        if (message == null || !message.matches("(?s).*[\\u3400-\\u9fff].*")) {
            return context.getString(fallback);
        }
        return message.replace("CampusLostFound", "校园寻物站")
                .replace("HTTP", "响应状态").replace("IP", "网络地址");
    }

    static int typeLabel(String type) { return "LOST".equals(type) ? R.string.ui_lost : R.string.ui_found; }

    static int stateLabel(String state) {
        switch (state) {
            case "OPEN": return R.string.ui_open;
            case "RESOLVED": return R.string.ui_resolved;
            case "CLOSED": return R.string.ui_closed;
            case "PENDING": return R.string.ui_pending;
            case "ACCEPTED": return R.string.ui_accepted;
            case "REJECTED": return R.string.ui_rejected;
            case "CANCELLED": return R.string.ui_cancelled;
            default: return R.string.ui_all;
        }
    }

    static String category(Activity activity, String value) {
        for (int i = 1; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(value)) return activity.getResources().getStringArray(R.array.ui_categories)[i];
        }
        return "";
    }

    static boolean validate(EditText input, int min, int max) {
        String value = input.getText().toString().trim();
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) {
            input.setError(input.getContext().getString(R.string.ui_field_invalid, min, max));
            input.requestFocus();
            return false;
        }
        input.setError(null);
        return true;
    }
}
