package com.example.campuslostfound.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import com.example.campuslostfound.R;
import org.json.JSONObject;

public class PostListActivity extends BusinessActivity {
    private EditText keyword;
    private Spinner type, category, status;
    private PostAdapter adapter;
    private int queryRound;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_post_list);
        bindPage(R.string.ui_home);
        keyword = findViewById(R.id.search_keyword);
        type = findViewById(R.id.filter_type);
        category = findViewById(R.id.filter_category);
        status = findViewById(R.id.filter_status);
        UiSupport.options(type, R.array.ui_types);
        UiSupport.options(category, R.array.ui_categories);
        UiSupport.options(status, R.array.ui_public_statuses);
        adapter = new PostAdapter(this);
        ListView list = findViewById(R.id.post_list);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                startActivity(new Intent(this, PostDetailActivity.class).putExtra("postId", id)));
        findViewById(R.id.search_posts).setOnClickListener(view -> loadPosts());
        findViewById(R.id.publish_post).setOnClickListener(view -> startActivity(new Intent(this, PublishPostActivity.class)));
        findViewById(R.id.my_records).setOnClickListener(view -> startActivity(new Intent(this, MyRecordsActivity.class)));
    }

    @Override protected void onReady() {
        ((TextView) findViewById(R.id.home_identity)).setText(getString(R.string.ui_welcome, nickname));
        loadPosts();
    }

    private void loadPosts() {
        if (!ready || busy) return;
        String text = keyword.getText().toString().trim();
        if (text.codePointCount(0, text.length()) > 40) {
            keyword.setError(getString(R.string.ui_keyword_long)); return;
        }
        int round = ++queryRound;
        setBusy(true);
        showState(getString(R.string.ui_loading), false);
        api.listPosts(UiSupport.TYPES[type.getSelectedItemPosition()],
                UiSupport.CATEGORIES[category.getSelectedItemPosition()], text.isEmpty() ? null : text,
                UiSupport.PUBLIC_STATUSES[status.getSelectedItemPosition()], (code, response, error) -> {
                    if (!visible || round != queryRound) return;
                    JSONObject data = result(code, response, error, false);
                    if (data == null) { adapter.replace(null); return; }
                    adapter.replace(data.optJSONArray("items"));
                    showState(getString(adapter.getCount() == 0 ? R.string.ui_empty_posts
                            : data.optBoolean("hasMore") ? R.string.ui_more : R.string.ui_records_count,
                            adapter.getCount()), false);
                });
    }

    @Override protected void onBusyChanged() {
        if (keyword == null) return;
        boolean enabled = ready && !busy;
        keyword.setEnabled(enabled); type.setEnabled(enabled); category.setEnabled(enabled); status.setEnabled(enabled);
        findViewById(R.id.search_posts).setEnabled(enabled);
        findViewById(R.id.publish_post).setEnabled(enabled);
        findViewById(R.id.my_records).setEnabled(enabled);
    }
}
