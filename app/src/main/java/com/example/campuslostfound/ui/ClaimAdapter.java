package com.example.campuslostfound.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import com.example.campuslostfound.R;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

final class ClaimAdapter extends BaseAdapter {
    interface Actions {
        void openPost(long id);
        void cancel(JSONObject claim);
        void confirm(JSONObject claim);
        void reject(JSONObject claim);
    }
    private final Activity activity;
    private final boolean received;
    private final Actions actions;
    private JSONArray items = new JSONArray();
    private final Set<Long> expanded = new HashSet<>();
    private boolean enabled;
    private boolean postOpen;

    ClaimAdapter(Activity activity, boolean received, Actions actions) {
        this.activity = activity; this.received = received; this.actions = actions;
    }
    void replace(JSONArray data, boolean open) {
        items = data == null ? new JSONArray() : data; postOpen = open; notifyDataSetChanged();
    }
    void enableActions(boolean value) { enabled = value; notifyDataSetChanged(); }
    @Override public int getCount() { return items.length(); }
    @Override public JSONObject getItem(int position) { return items.optJSONObject(position); }
    @Override public long getItemId(int position) { return getItem(position).optLong("id"); }
    @Override public View getView(int position, View recycled, ViewGroup parent) {
        View row = recycled == null ? LayoutInflater.from(activity).inflate(R.layout.item_claim, parent, false) : recycled;
        JSONObject claim = getItem(position);
        long id = claim.optLong("id");
        set(row, R.id.claim_title, UiSupport.text(claim, "postTitle"));
        row.findViewById(R.id.claim_title).setVisibility(received ? View.GONE : View.VISIBLE);
        set(row, R.id.claim_state, activity.getString(R.string.ui_record_state,
                activity.getString(UiSupport.stateLabel(claim.optString("postStatus"))),
                activity.getString(UiSupport.stateLabel(claim.optString("status")))));
        set(row, R.id.claim_applicant, received ? activity.getString(R.string.ui_applicant,
                UiSupport.text(claim.optJSONObject("applicant"), "nickname"))
                : activity.getString(UiSupport.typeLabel(claim.optString("postType"))));
        ((TextView) row.findViewById(R.id.claim_applicant)).setTextAppearance(
                received ? R.style.UiSection : R.style.UiBody);
        set(row, R.id.claim_date, UiSupport.date(claim.optLong("createdAt")));
        set(row, R.id.claim_proof_value, activity.getString(R.string.ui_proof_value, UiSupport.text(claim, "proof")));
        set(row, R.id.claim_contact_value, activity.getString(R.string.ui_contact_value, UiSupport.text(claim, "contact")));
        String note = UiSupport.text(claim, "note");
        set(row, R.id.claim_note, note.isEmpty() ? "" : activity.getString(R.string.ui_note_value, note));
        row.findViewById(R.id.claim_note).setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.claim_contents).setVisibility(received || expanded.contains(id) ? View.VISIBLE : View.GONE);
        row.findViewById(R.id.claim_expand).setVisibility(received ? View.GONE : View.VISIBLE);
        ((TextView) row.findViewById(R.id.claim_expand)).setText(expanded.contains(id) ? R.string.ui_collapse : R.string.ui_expand);
        row.findViewById(R.id.claim_expand).setOnClickListener(view -> {
            if (!expanded.add(id)) expanded.remove(id);
            notifyDataSetChanged();
        });
        TextView openPost = row.findViewById(R.id.claim_open_post);
        boolean deleted = claim.optBoolean("postDeleted");
        openPost.setText(deleted ? R.string.ui_post_deleted : R.string.ui_record_detail);
        openPost.setEnabled(!deleted);
        openPost.setOnClickListener(view -> { if (!deleted) actions.openPost(claim.optLong("postId")); });
        boolean pending = "PENDING".equals(claim.optString("status")) && "OPEN".equals(claim.optString("postStatus"));
        bindAction(row, R.id.claim_cancel, !received && pending, () -> actions.cancel(claim));
        bindAction(row, R.id.claim_confirm, received && postOpen && pending, () -> actions.confirm(claim));
        bindAction(row, R.id.claim_reject, received && postOpen && pending, () -> actions.reject(claim));
        return row;
    }
    private void set(View row, int id, String text) { ((TextView) row.findViewById(id)).setText(text); }
    private void bindAction(View row, int id, boolean show, Runnable action) {
        View button = row.findViewById(id);
        button.setVisibility(show ? View.VISIBLE : View.GONE);
        button.setEnabled(enabled);
        button.setOnClickListener(view -> { if (enabled) action.run(); });
    }
}
