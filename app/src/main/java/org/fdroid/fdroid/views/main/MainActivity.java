/*
 * Copyright (C) 2016-2017 Peter Serwylo
 * Copyright (C) 2017 Christine Emrich
 * Copyright (C) 2017 Hans-Christoph Steiner
 * Copyright (C) 2018 Senecto Limited
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */

package org.fdroid.fdroid.views.main;

import android.Manifest;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.snackbar.BaseTransientBottomBar;

import org.fdroid.fdroid.AppUpdateStatusManager;
import org.fdroid.fdroid.FDroidApp;
import org.fdroid.fdroid.Preferences;
import org.fdroid.fdroid.R;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.nearby.SDCardScannerService;
import org.fdroid.fdroid.nearby.SwapService;
import org.fdroid.fdroid.nearby.TreeUriScannerIntentService;
import org.fdroid.fdroid.nearby.WifiStateChangeService;
import org.fdroid.fdroid.views.AppDetailsActivity;
import org.fdroid.fdroid.views.apps.AppListActivity;
import org.fdroid.fdroid.work.RepoUpdateWorker;

/**
 * Main view shown to users upon starting F-Droid.
 * <p>
 * Shows a bottom navigation bar, with the following entries:
 * + What's new
 * + Categories list
 * + App swap
 * + Updates
 * + Settings
 * <p>
 * Users navigate between items by using the bottom navigation bar, or by swiping left and right.
 * When switching from one screen to the next, we stay within this activity. The new screen will
 * get inflated (if required)
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    public static final String EXTRA_VIEW_LATEST = "org.fdroid.fdroid.views.main.MainActivity.VIEW_LATEST";
    private static final String EXTRA_VIEW_CATEGORIES = "org.fdroid.fdroid.views.main.MainActivity.VIEW_CATEGORIES";
    private static final String EXTRA_VIEW_NEARBY = "org.fdroid.fdroid.views.main.MainActivity.VIEW_NEARBY";
    public static final String EXTRA_VIEW_UPDATES = "org.fdroid.fdroid.views.main.MainActivity.VIEW_UPDATES";
    public static final String EXTRA_VIEW_SETTINGS = "org.fdroid.fdroid.views.main.MainActivity.VIEW_SETTINGS";

    static final int REQUEST_LOCATION_PERMISSIONS = 0xEF0F;
    static final int REQUEST_STORAGE_PERMISSIONS = 0xB004;
    static final int REQUEST_STORAGE_ACCESS = 0x40E5;

    public static final String ACTION_REQUEST_SWAP = "requestSwap";

    private RecyclerView pager;
    private MainViewAdapter adapter;
    private BottomNavigationView bottomNavigation;
    private BadgeDrawable updatesBadge;
    private Snackbar snackbar = null;
    private int snackbarShowing = 0;
    private int currentTab = -1;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                // no-op
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        FDroidApp fdroidApp = (FDroidApp) getApplication();
        fdroidApp.setSecureWindow(this);

        fdroidApp.applyPureBlackBackgroundInDarkTheme(this);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        adapter = new MainViewAdapter(this);

        pager = findViewById(R.id.main_view_pager);
        pager.setHasFixedSize(true);
        pager.setLayoutManager(new NonScrollingHorizontalLayoutManager(this));
        pager.setAdapter(adapter);

        bottomNavigation = findViewById(R.id.bottom_navigation);
        setSelectedMenuInNav(Preferences.get().getBottomNavigationViewName());
        bottomNavigation.setOnNavigationItemSelectedListener(item -> {
            int oldItemId = bottomNavigation.getSelectedItemId();
            int newItemId = item.getItemId();
            if (oldItemId != newItemId) {
                pager.scrollToPosition(item.getOrder());
                updateFabIfNecessary(item.getOrder());

                if (newItemId == R.id.latest) {
                    Preferences.get().setBottomNavigationViewName(EXTRA_VIEW_LATEST);
                } else if (newItemId == R.id.categories) {
                    Preferences.get().setBottomNavigationViewName(EXTRA_VIEW_CATEGORIES);
                } else if (newItemId == R.id.nearby) {
                    Preferences.get().setBottomNavigationViewName(EXTRA_VIEW_NEARBY);

                    NearbyViewBinder.updateUsbOtg(MainActivity.this);
                } else if (newItemId == R.id.updates) {
                    Preferences.get().setBottomNavigationViewName(EXTRA_VIEW_UPDATES);
                } else if (newItemId == R.id.settings) {
                    Preferences.get().setBottomNavigationViewName(EXTRA_VIEW_SETTINGS);
                    if (snackbar != null && snackbarShowing == 1) snackbar.dismiss();
                }
            }
            return true;

        });
        updatesBadge = bottomNavigation.getOrCreateBadge(R.id.updates);
        updatesBadge.setVisible(false);

        initialRepoUpdateIfRequired();

        AppUpdateStatusManager.getInstance(this).getNumUpdatableApps().observe(this, this::refreshUpdatesBadge);

        showUpgradeAntiFeaturesSnackbar();

        Intent intent = getIntent();
        if (handleMainViewSelectIntent(intent)) {
            return;
        }
        handleSearchOrAppViewIntent(intent);
    }

    /**
     * {@link android.material.navigation.NavigationBarView} says "Menu items
     * can also be used for programmatically selecting which destination is
     * currently active. It can be done using {@code MenuItem.setChecked(true)}".
     */
    private void setSelectedMenuInNav(int menuId) {
        if (currentTab >= 0 && menuId == bottomNavigation.getSelectedItemId()) return;
        int position = adapter.adapterPositionFromItemId(menuId);
        if (position < 0) {
            Log.e(TAG, "Invalid menu position: " + position);
        } else {
            pager.scrollToPosition(position);
            updateFabIfNecessary(position);
            bottomNavigation.getMenu().getItem(position).setChecked(true);
        }
    }

    private void setSelectedMenuInNav(final String viewName) {
        if (EXTRA_VIEW_LATEST.equals(viewName)) {
            setSelectedMenuInNav(R.id.latest);
        } else if (EXTRA_VIEW_CATEGORIES.equals(viewName)) {
            setSelectedMenuInNav(R.id.categories);
        } else if (EXTRA_VIEW_NEARBY.equals(viewName)) {
            setSelectedMenuInNav(R.id.nearby);
        } else if (EXTRA_VIEW_UPDATES.equals(viewName)) {
            setSelectedMenuInNav(R.id.updates);
        } else if (EXTRA_VIEW_SETTINGS.equals(viewName)) {
            setSelectedMenuInNav(R.id.settings);
        }
    }

    private void initialRepoUpdateIfRequired() {
        if (Preferences.get().isIndexNeverUpdated() &&
                !FDroidApp.getRepoUpdateManager(this).isUpdating().getValue()) {
            Utils.debugLog(TAG, "We haven't done an update yet. Forcing repo update.");
            RepoUpdateWorker.updateNow(this);
        }
    }

    private static void adjustFab(@NonNull View view, int h, boolean showing) {
        View fab = view.findViewById(R.id.fab_search);
        if (fab != null) {
            int toAdj = showing ? h : -h;
            if (toAdj != 0) {
                fab.setTranslationY(fab.getTranslationY() - toAdj);
            }
        }
    }

    private void updateFabIfNecessary(int tab, boolean showing, boolean forced) {
        RecyclerView.ViewHolder holder = pager.findViewHolderForAdapterPosition(tab);
        if (holder != null) {
            adjustFab(holder.itemView, snackbar.getView().getHeight(), showing);
        }
    }

    private void updateFabIfNecessary(boolean showing) {
        updateFabIfNecessary(currentTab, showing, true);
    }

    private void updateFabIfNecessary(int newTab) {
        if (snackbar != null && newTab != currentTab && snackbar.isShown()) {
            updateFabIfNecessary(currentTab, false, false);
            pager.post(() -> updateFabIfNecessary(newTab, true, false));
        }
        currentTab = newTab;
    }

    private void showUpgradeAntiFeaturesSnackbar() {
        if (Preferences.get().pendingAntiFeaturesUpgrade()) {
            if (currentTab != adapter.adapterPositionFromItemId(R.id.settings)) {
                if (snackbar == null) {
                    snackbar = Snackbar.make(pager, R.string.antifeatures_updated,
                            BaseTransientBottomBar.LENGTH_INDEFINITE);
                    snackbar.addCallback(new BaseTransientBottomBar.BaseCallback<Snackbar>() {
                        @Override
                        public void onDismissed(Snackbar b, int event) {
                            updateFabIfNecessary(false);
                            snackbarShowing = 0;
                        }

                        @Override
                        public void onShown(Snackbar b) {
                            snackbarShowing = 1;
                            pager.post(() -> updateFabIfNecessary(true));
                        }
                    });
                    snackbar.setAction(R.string.antifeatures_review, v -> {
                        setSelectedMenuInNav(R.id.settings);
                    });
                }
                bottomNavigation.post(() -> {
                    View layout = snackbar.getView();
                    LayoutParams lp = layout.getLayoutParams();
                    if (lp instanceof MarginLayoutParams) {
                        ((MarginLayoutParams) lp).bottomMargin += bottomNavigation.getHeight();
                        layout.setLayoutParams(lp);
                    }
                    snackbar.show();
                });
            }
            BadgeDrawable settingsBadge = bottomNavigation.getOrCreateBadge(R.id.settings);
            settingsBadge.setNumber(1);
            settingsBadge.setVisible(true);
            Preferences.get().registerAppsRequiringAntiFeaturesChangeListener(new Preferences.ChangeListener() {
                @Override
                public void onPreferenceChange() {
                    if (!Preferences.get().pendingAntiFeaturesUpgrade()) {
                        if (snackbar != null && snackbarShowing == 1) snackbar.dismiss();
                        settingsBadge.setVisible(false);
                        settingsBadge.clearNumber();
                        Preferences.get().unregisterAppsRequiringAntiFeaturesChangeListener(this);
                    }
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        FDroidApp.checkStartTor(this, Preferences.get());

        NearbyViewBinder.updateExternalStorageViews(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // don't request this in onResume, because the launcher causes a call to that,
        // even if permission permanently denied, so we'll get an infinite loop
        if (Build.VERSION.SDK_INT >= 33) {
            String notificationPerm = Manifest.permission.POST_NOTIFICATIONS;
            if (ContextCompat.checkSelfPermission(this, notificationPerm) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(notificationPerm);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (handleMainViewSelectIntent(intent)) {
            return;
        }

        handleSearchOrAppViewIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_STORAGE_ACCESS) {
            TreeUriScannerIntentService.onActivityResult(this, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSIONS) {
            WifiStateChangeService.start(this, null);
            ContextCompat.startForegroundService(this, new Intent(this, SwapService.class));
        } else if (requestCode == REQUEST_STORAGE_PERMISSIONS) {
            Toast.makeText(this,
                    this.getString(R.string.scan_removable_storage_toast, ""),
                    Toast.LENGTH_SHORT).show();
            SDCardScannerService.scan(this);
        }
    }

    /**
     * Handle an {@link Intent} that shows a specific tab in the main view.
     */
    private boolean handleMainViewSelectIntent(Intent intent) {
        if (intent.hasExtra(EXTRA_VIEW_NEARBY)) {
            setSelectedMenuInNav(R.id.nearby);
            return true;
        } else if (intent.hasExtra(EXTRA_VIEW_UPDATES)) {
            setSelectedMenuInNav(R.id.updates);
            return true;
        } else if (intent.hasExtra(EXTRA_VIEW_SETTINGS)) {
            setSelectedMenuInNav(R.id.settings);
            return true;
        }
        return false;
    }

    /**
     * Since any app could send this {@link Intent}, and the search terms are
     * fed into a SQL query, the data must be strictly sanitized to avoid
     * SQL injection attacks.
     */
    private void handleSearchOrAppViewIntent(Intent intent) {
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            if (query != null) performSearch(query);
            return;
        }

        final Uri data = intent.getData();
        if (data == null) {
            return;
        }

        final String scheme = data.getScheme();
        final String path = data.getPath();
        String packageName = null;
        String query = null;
        if (data.isHierarchical()) {
            final String host = data.getHost();
            if (host == null) {
                return;
            }
            switch (host) {
                case "f-droid.org":
                case "www.f-droid.org":
                case "staging.f-droid.org":
                    if (path.startsWith("/app/") || path.startsWith("/packages/")
                            || path.matches("^/[a-z][a-z][a-zA-Z_-]*/packages/.*")) {
                        // http://f-droid.org/app/packageName
                        packageName = data.getLastPathSegment();
                    } else if (path.startsWith("/repository/browse")) {
                        // http://f-droid.org/repository/browse?fdfilter=search+query
                        query = data.getQueryParameter("fdfilter");

                        // http://f-droid.org/repository/browse?fdid=packageName
                        packageName = data.getQueryParameter("fdid");
                    } else if ("/app".equals(data.getPath()) || "/packages".equals(data.getPath())) {
                        packageName = null;
                    }
                    break;
                case "details":
                    // market://details?id=app.id
                    packageName = data.getQueryParameter("id");
                    break;
                case "search":
                    // market://search?q=query
                    query = data.getQueryParameter("q");
                    break;
                case "play.google.com":
                    if (path.startsWith("/store/apps/details")) {
                        // http://play.google.com/store/apps/details?id=app.id
                        packageName = data.getQueryParameter("id");
                    } else if (path.startsWith("/store/search")) {
                        // http://play.google.com/store/search?q=foo
                        query = data.getQueryParameter("q");
                    }
                    break;
                case "apps":
                case "amazon.com":
                case "www.amazon.com":
                    // amzn://apps/android?p=app.id
                    // http://amazon.com/gp/mas/dl/android?s=app.id
                    packageName = data.getQueryParameter("p");
                    query = data.getQueryParameter("s");
                    break;
            }
        } else if ("fdroid.app".equals(scheme)) {
            // fdroid.app:app.id
            packageName = data.getSchemeSpecificPart();
        } else if ("fdroid.search".equals(scheme)) {
            // fdroid.search:query
            query = data.getSchemeSpecificPart();
        }

        if (!TextUtils.isEmpty(query)) {
            // an old format for querying via packageName
            if (query.startsWith("pname:")) {
                packageName = query.split(":")[1];
            }

            // sometimes, search URLs include pub: or other things before the query string
            if (query.contains(":")) {
                query = query.split(":")[1];
            }
        }

        if (!TextUtils.isEmpty(packageName)) {
            // sanitize packageName to be a valid Java packageName and prevent exploits
            packageName = packageName.replaceAll("[^A-Za-z\\d_.]", "");
            Utils.debugLog(TAG, "FDroid launched via app link for '" + packageName + "'");
            Intent intentToInvoke = new Intent(this, AppDetailsActivity.class);
            intentToInvoke.putExtra(AppDetailsActivity.EXTRA_APPID, packageName);
            startActivity(intentToInvoke);
            finish();
        } else if (!TextUtils.isEmpty(query)) {
            Utils.debugLog(TAG, "FDroid launched via search link for '" + query + "'");
            performSearch(query);
        }
    }

    /**
     * These strings might end up in a SQL query, so strip all non-alpha-num
     */
    static String sanitizeSearchTerms(@NonNull String query) {
        return query.replaceAll("[^\\p{L}\\d_ -]", " ");
    }

    /**
     * Initiates the {@link AppListActivity} with the relevant search terms passed in via the query arg.
     */
    private void performSearch(@NonNull String query) {
        Intent searchIntent = new Intent(this, AppListActivity.class);
        searchIntent.putExtra(AppListActivity.EXTRA_SEARCH_TERMS, sanitizeSearchTerms(query));
        startActivity(searchIntent);
    }

    private void refreshUpdatesBadge(int canUpdateCount) {
        if (canUpdateCount <= 0) {
            updatesBadge.setVisible(false);
            updatesBadge.clearNumber();
        } else {
            updatesBadge.setNumber(canUpdateCount);
            updatesBadge.setVisible(true);
        }
    }

    private static class NonScrollingHorizontalLayoutManager extends LinearLayoutManager {
        NonScrollingHorizontalLayoutManager(Context context) {
            super(context, LinearLayoutManager.HORIZONTAL, false);
        }

        @Override
        public boolean canScrollHorizontally() {
            return false;
        }

        @Override
        public boolean canScrollVertically() {
            return false;
        }
    }
}