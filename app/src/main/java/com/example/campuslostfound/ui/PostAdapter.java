package com.example.campuslostfound.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.example.campuslostfound.R;
import org.json.JSONArray;
import org.json.JSONObject;

final class PostAdapter extends BaseAdapter {
    private final Activity activity;
    private JSONArray items = new JSONArray();
    PostAdapter(Activity activity) { this.activity = activity; }
    void replace(JSONArray data) { items = data == null ? new JSONArray() : data; notifyDataSetChanged(); }
    @Override public int getCount() { return items.length(); }
    @Override public JSONObject getItem(int position) { return items.optJSONObject(position); }
    @Override public long getItemId(int position) { return getItem(position).optLong("id"); }
    @Override public View getView(int position, View recycled, ViewGroup parent) {
        View row = recycled == null ? LayoutInflater.from(activity).inflate(R.layout.item_post, parent, false) : recycled;
        JSONObject post = getItem(position);
        TextView type = row.findViewById(R.id.post_type);
        type.setText(UiSupport.typeLabel(post.optString("type")));
        boolean lost = "LOST".equals(post.optString("type"));
        type.setTextColor(ContextCompat.getColor(activity, lost ? R.color.ui_lost : R.color.ui_primary));
        ((TextView) row.findViewById(R.id.post_state)).setText(UiSupport.stateLabel(post.optString("status")));
        ((TextView) row.findViewById(R.id.post_title)).setText(UiSupport.text(post, "title"));
        ((TextView) row.findViewById(R.id.post_location)).setText(activity.getString(R.string.ui_post_meta,
                UiSupport.text(post, "location"), UiSupport.category(activity, post.optString("category"))));
        ((TextView) row.findViewById(R.id.post_date)).setText(UiSupport.date(post.optLong("createdAt")));
        return row;
    }
}
