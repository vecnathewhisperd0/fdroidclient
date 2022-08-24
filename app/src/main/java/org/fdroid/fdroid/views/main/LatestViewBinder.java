package org.fdroid.fdroid.views.main;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.fdroid.fdroid.Preferences;
import org.fdroid.fdroid.R;
import org.fdroid.fdroid.UpdateService;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.data.AppProvider;
import org.fdroid.fdroid.data.RepoProvider;
import org.fdroid.fdroid.data.Schema.AppMetadataTable;
import org.fdroid.fdroid.data.Schema.AppMetadataTable.Cols;
import org.fdroid.fdroid.data.Schema.RepoTable;
import org.fdroid.fdroid.panic.HidingManager;
import org.fdroid.fdroid.views.apps.AppListActivity;
import org.fdroid.fdroid.views.categories.AppCardController;

import java.util.Date;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * Loads a list of newly added or recently updated apps and displays them to the user.
 */
class LatestViewBinder implements LoaderManager.LoaderCallbacks<Cursor> {

    private static final String TAG = "TEMP_LOG"; // "LatestViewBinder";

    private static final int LOADER_ID = 978015789;

    private final LatestAdapter latestAdapter;
    private final AppCompatActivity activity;
    private final TextView emptyState;
    private final RecyclerView appList;

    private ProgressBar progressBar;

    private boolean repoError;

    LatestViewBinder(final AppCompatActivity activity, FrameLayout parent) {
        this.activity = activity;

        View latestView = activity.getLayoutInflater().inflate(R.layout.main_tab_latest, parent, true);

        latestAdapter = new LatestAdapter(activity);

        GridLayoutManager layoutManager = new GridLayoutManager(activity, 2);
        layoutManager.setSpanSizeLookup(new LatestAdapter.SpanSizeLookup());

        emptyState = (TextView) latestView.findViewById(R.id.empty_state);

        appList = (RecyclerView) latestView.findViewById(R.id.app_list);
        appList.setHasFixedSize(true);
        appList.setLayoutManager(layoutManager);
        appList.setAdapter(latestAdapter);

        final SwipeRefreshLayout swipeToRefresh = (SwipeRefreshLayout) latestView
                .findViewById(R.id.swipe_to_refresh);
        Utils.applySwipeLayoutColors(swipeToRefresh);
        swipeToRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                Log.d(TAG, "swipe to refresh latest");
                swipeToRefresh.setRefreshing(false);
                UpdateService.updateNow(activity);
            }
        });

        FloatingActionButton searchFab = (FloatingActionButton) latestView.findViewById(R.id.fab_search);
        searchFab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.startActivity(new Intent(activity, AppListActivity.class));
            }
        });
        searchFab.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if (Preferences.get().hideOnLongPressSearch()) {
                    HidingManager.showHideDialog(activity);
                    return true;
                } else {
                    return false;
                }
            }
        });

        activity.getSupportLoaderManager().initLoader(LOADER_ID, null, this);
    }

    /**
     * Sort by localized first so users see entries in their language,
     * then sort by highlighted fields, then sort by whether the app is new,
     * then if it has WhatsNew/Changelog entries, then by when it was last
     * updated.  Last, it sorts by the date the app was added, putting older
     * ones first, to give preference to apps that have been maintained in
     * F-Droid longer.
     *
     * @see AppProvider#getLatestTabUri()
     */
    @NonNull
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        if (id != LOADER_ID) {
            return null;
        }
        final String table = AppMetadataTable.NAME;
        final String added = table + "." + Cols.ADDED;
        final String lastUpdated = table + "." + Cols.LAST_UPDATED;
        return new CursorLoader(
                activity,
                AppProvider.getLatestTabUri(),
                AppMetadataTable.Cols.ALL,
                Utils.getAntifeatureSQLFilter(activity),
                null,
                table + "." + Cols.IS_LOCALIZED + " DESC"
                        + ", " + table + "." + Cols.NAME + " IS NULL ASC"
                        + ", CASE WHEN " + table + "." + Cols.ICON + " IS NULL"
                        + "        AND " + table + "." + Cols.ICON_URL + " IS NULL"
                        + "        THEN 1 ELSE 0 END"
                        + ", " + table + "." + Cols.SUMMARY + " IS NULL ASC"
                        + ", " + table + "." + Cols.DESCRIPTION + " IS NULL ASC"
                        + ", CASE WHEN " + table + "." + Cols.PHONE_SCREENSHOTS + " IS NULL"
                        + "        AND " + table + "." + Cols.SEVEN_INCH_SCREENSHOTS + " IS NULL"
                        + "        AND " + table + "." + Cols.TEN_INCH_SCREENSHOTS + " IS NULL"
                        + "        AND " + table + "." + Cols.TV_SCREENSHOTS + " IS NULL"
                        + "        AND " + table + "." + Cols.WEAR_SCREENSHOTS + " IS NULL"
                        + "        AND " + table + "." + Cols.FEATURE_GRAPHIC + " IS NULL"
                        + "        AND " + table + "." + Cols.PROMO_GRAPHIC + " IS NULL"
                        + "        AND " + table + "." + Cols.TV_BANNER + " IS NULL"
                        + "        THEN 1 ELSE 0 END"
                        + ", CASE WHEN date(" + added + ")  >= date(" + lastUpdated + ")"
                        + "        AND date((SELECT " + RepoTable.Cols.LAST_UPDATED + " FROM " + RepoTable.NAME
                        + "                  WHERE _id=" + table + "." + Cols.REPO_ID
                        + "                  ),'-" + AppCardController.DAYS_TO_CONSIDER_NEW + " days') "
                        + "          < date(" + lastUpdated + ")"
                        + "        THEN 0 ELSE 1 END"
                        + ", " + table + "." + Cols.WHATSNEW + " IS NULL ASC"
                        + ", " + lastUpdated + " DESC"
                        + ", " + added + " ASC");
    }

    @Override
    public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {

        Log.d(TAG, "onLoadFinished triggered");

        if (loader.getId() != LOADER_ID) {
            return;
        }

        latestAdapter.setAppsCursor(cursor);

        if (latestAdapter.getItemCount() == 0) {
            Log.d(TAG, "onLoadFinished no items");
            emptyState.setVisibility(View.VISIBLE);
            appList.setVisibility(View.GONE);
            explainEmptyStateToUser();
        } else {
            Log.d(TAG, "onLoadFinished got items");
            emptyState.setVisibility(View.GONE);
            appList.setVisibility(View.VISIBLE);
        }
    }

    private void explainEmptyStateToUser() {
        // is this what creates the spinner at the top of the page?
        if (Preferences.get().isIndexNeverUpdated() && UpdateService.isUpdating()) {
            Log.d(TAG, "never updated and is updating");
            if (progressBar != null) {
                Log.d(TAG, "loading spinner exists(?)");
                return;
            }
            Log.d(TAG, "create loading spinner(?)");
            LinearLayout linearLayout = (LinearLayout) appList.getParent();
            progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleLarge);
            progressBar.setId(R.id.progress_bar);
            linearLayout.addView(progressBar);
            emptyState.setVisibility(View.GONE);
            appList.setVisibility(View.GONE);
            return;
        }

        StringBuilder emptyStateText = new StringBuilder();
        //emptyStateText.append(activity.getString(R.string.latest__empty_state__no_recent_apps));
        //emptyStateText.append("\n\n");

        int repoCount = RepoProvider.Helper.countEnabledRepos(activity);
        if (repoCount == 0) {
            Log.d(TAG, "no repos enabled");
            emptyStateText.append(activity.getString(R.string.latest__empty_state__no_enabled_repos));
        } else {
            Date lastUpdate = RepoProvider.Helper.lastUpdate(activity);
            // TODO: improve error handling?  currently local variable is set by a received broadcast
            // because explainEmptyStateToUser() may be called separately from onLoadFinished()
            // is it reasonable for an error to override other possible causes of an empty state?
            if (repoError) {
                Log.d(TAG, "alredy got repo error during update");
                emptyStateText.append(activity.getString(R.string.latest__empty_state__no_recent_apps));
            } else if (lastUpdate == null) {
                Log.d(TAG, "never been updated");
                emptyStateText.append(activity.getString(R.string.latest__empty_state__never_updated));
            } else {
                Log.d(TAG, "was previously updated");
                emptyStateText.append(Utils.formatLastUpdated(activity.getResources(), lastUpdate));
            }
        }

        emptyState.setText(emptyStateText.toString());
    }

    @Override
    public void onLoaderReset(@NonNull Loader<Cursor> loader) {
        Log.d(TAG, "binder.onLoaderReset triggered");
        if (loader.getId() != LOADER_ID) {
            return;
        }

        latestAdapter.setAppsCursor(null);
    }

    public void handleError() {
        Log.d(TAG, "binder.handleError triggered");
        repoError = true;
        explainEmptyStateToUser();
    }
}
