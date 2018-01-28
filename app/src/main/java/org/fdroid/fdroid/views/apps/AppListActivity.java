package org.fdroid.fdroid.views.apps;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import org.fdroid.fdroid.FDroidApp;
import org.fdroid.fdroid.R;
import org.fdroid.fdroid.data.AppProvider;
import org.fdroid.fdroid.data.Schema;

import static org.fdroid.fdroid.R.menu.app_list_activity;

public class AppListActivity extends AppCompatActivity implements LoaderManager.LoaderCallbacks<Cursor>, CategoryTextWatcher.SearchTermsChangedListener {

    public static final String EXTRA_CATEGORY = "org.fdroid.fdroid.views.apps.AppListActivity.EXTRA_CATEGORY";
    public static final String EXTRA_SEARCH_TERMS = "org.fdroid.fdroid.views.apps.AppListActivity.EXTRA_SEARCH_TERMS";

    private RecyclerView appView;
    private AppListAdapter appAdapter;
    private String category;
    private String searchTerms;
    private String sortClauseSelected = SortClause.LAST_UPDATED;
    private TextView emptyState;
    private EditText searchInput;

    private interface SortClause {
        String NAME = "fdroid_app.name asc";
        String LAST_UPDATED = "fdroid_app.lastUpdated desc";
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ((FDroidApp) getApplication()).applyTheme(this);
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_app_list);
        final android.support.v7.widget.Toolbar toolbar = (android.support.v7.widget.Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        searchInput = (EditText) findViewById(R.id.search);
        searchInput.addTextChangedListener(new CategoryTextWatcher(this, searchInput, this));
        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    // Hide the keyboard (http://stackoverflow.com/a/1109108 (when pressing search)
                    InputMethodManager inputManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    inputManager.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);

                    // Change focus from the search input to the app list.
                    appView.requestFocus();
                    return true;
                }
                return false;
            }
        });

        emptyState = (TextView) findViewById(R.id.empty_state);

        View clearButton = findViewById(R.id.clear);
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchInput.setText("");
            }
        });

        appAdapter = new AppListAdapter(this);

        appView = (RecyclerView) findViewById(R.id.app_list);
        appView.setHasFixedSize(true);
        appView.setLayoutManager(new LinearLayoutManager(this));
        appView.setAdapter(appAdapter);

        parseIntentForSearchQuery();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(app_list_activity, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            default:
                return super.onOptionsItemSelected(item);

            case R.id.action_sort:
                return true;

            case R.id.action_sort_by_name:
                sortClauseSelected = SortClause.NAME;
                break;

            case R.id.action_sort_by_last_updated:
                sortClauseSelected = SortClause.LAST_UPDATED;
                break;
        }

        // Common to selected items
        item.setChecked(true);
        getSupportLoaderManager().restartLoader(0, null, this);
        appView.scrollToPosition(0);
        return true;
    }

    private void parseIntentForSearchQuery() {
        Intent intent = getIntent();
        category = intent.hasExtra(EXTRA_CATEGORY) ? intent.getStringExtra(EXTRA_CATEGORY) : null;
        searchTerms = intent.hasExtra(EXTRA_SEARCH_TERMS) ? intent.getStringExtra(EXTRA_SEARCH_TERMS) : null;

        searchInput.setText(getSearchText(category, searchTerms));
        searchInput.setSelection(searchInput.getText().length());

        if (category != null) {
            // Do this so that the search input does not get focus by default. This allows for a user
            // experience where the user scrolls through the apps in the category.
            appView.requestFocus();
        }

        getSupportLoaderManager().initLoader(0, null, this);
    }

    private CharSequence getSearchText(@Nullable String category, @Nullable String searchTerms) {
        StringBuilder string = new StringBuilder();
        if (category != null) {
            string.append(category).append(":");
        }

        if (searchTerms != null) {
            string.append(searchTerms);
        }

        return string.toString();
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return new CursorLoader(this, AppProvider.getSearchUri(searchTerms, category), Schema.AppMetadataTable.Cols.ALL, null, null, sortClauseSelected);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        appAdapter.setAppCursor(cursor);
        if (cursor.getCount() > 0) {
            emptyState.setVisibility(View.GONE);
            appView.setVisibility(View.VISIBLE);
        } else {
            emptyState.setVisibility(View.VISIBLE);
            appView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        appAdapter.setAppCursor(null);
    }

    @Override
    public void onSearchTermsChanged(@Nullable String category, @NonNull String searchTerms) {
        this.category = category;
        this.searchTerms = searchTerms;
        getSupportLoaderManager().restartLoader(0, null, this);
    }
}
