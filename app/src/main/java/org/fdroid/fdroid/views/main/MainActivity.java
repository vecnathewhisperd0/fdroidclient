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

import static org.fdroid.fdroid.UpdateService.EXTRA_MESSAGE;
import static org.fdroid.fdroid.UpdateService.EXTRA_PROGRESS;
import static org.fdroid.fdroid.UpdateService.EXTRA_STATUS_CODE;
import static org.fdroid.fdroid.UpdateService.LOCAL_ACTION_STATUS;
import static org.fdroid.fdroid.UpdateService.STATUS_ERROR_GLOBAL;
import static org.fdroid.fdroid.UpdateService.STATUS_ERROR_LOCAL;
import static org.fdroid.fdroid.UpdateService.STATUS_ERROR_LOCAL_SMALL;
import static org.fdroid.fdroid.UpdateService.STATUS_INFO;

import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.fdroid.fdroid.AppUpdateStatusManager;
import org.fdroid.fdroid.AppUpdateStatusManager.AppUpdateStatus;
import org.fdroid.fdroid.FDroidApp;
import org.fdroid.fdroid.NfcHelper;
import org.fdroid.fdroid.Preferences;
import org.fdroid.fdroid.R;
import org.fdroid.fdroid.UpdateService;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.data.NewRepoConfig;
import org.fdroid.fdroid.nearby.SDCardScannerService;
import org.fdroid.fdroid.nearby.SwapService;
import org.fdroid.fdroid.nearby.SwapWorkflowActivity;
import org.fdroid.fdroid.nearby.TreeUriScannerIntentService;
import org.fdroid.fdroid.nearby.WifiStateChangeService;
import org.fdroid.fdroid.net.DownloaderService;
import org.fdroid.fdroid.views.AppDetailsActivity;
import org.fdroid.fdroid.views.ManageReposActivity;
import org.fdroid.fdroid.views.apps.AppListActivity;

import org.fdroid.fdroid.BuildConfig;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.util.Log;
import org.greatfire.envoy.*;
import org.json.JSONArray;
import org.json.JSONObject;

import IEnvoyProxy.IEnvoyProxy;

/**
 * Main view shown to users upon starting F-Droid.
 * <p>
 * Shows a bottom navigation bar, with the following entries:
 * + Whats new
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

    private static final String TAG = "TEMP_LOG"; // "MainActivity";

    public static final String EXTRA_VIEW_UPDATES = "org.fdroid.fdroid.views.main.MainActivity.VIEW_UPDATES";
    public static final String EXTRA_VIEW_NEARBY = "org.fdroid.fdroid.views.main.MainActivity.VIEW_NEARBY";
    public static final String EXTRA_VIEW_SETTINGS = "org.fdroid.fdroid.views.main.MainActivity.VIEW_SETTINGS";

    static final int REQUEST_LOCATION_PERMISSIONS = 0xEF0F;
    static final int REQUEST_STORAGE_PERMISSIONS = 0xB004;
    public static final int REQUEST_STORAGE_ACCESS = 0x40E5;

    private static final String ADD_REPO_INTENT_HANDLED = "addRepoIntentHandled";

    private static final String ACTION_ADD_REPO = "org.fdroid.fdroid.MainActivity.ACTION_ADD_REPO";
    public static final String ACTION_REQUEST_SWAP = "requestSwap";

    private RecyclerView pager;
    private MainViewAdapter adapter;

    private int currentPageId = 0;
    public static final String CURRENT_PAGE_ID = "org.greatfire.envoy.CURRENT_PAGE_ID";

    // local and remote urls for proxy services
    private String ssUrlLocal = "socks5://127.0.0.1:1080";
    private String ssUrlRemote = "";
    private String hysteriaUrlLocal = "socks5://127.0.0.1:";
    private String hysteriaUrlRemote = "";
    // lists of proxy urls to validate with envoy
    private List<String> defaultUrls = new ArrayList<String>();
    private List<String> dnsttUrls = new ArrayList<String>();

    // TODO: revisit and refactor
    private boolean waitingForDnstt = false;
    private boolean waitingForHysteria = false;
    private boolean waitingForShadowsocks = false;
    private boolean waitingForDirectConnection = false;
    private boolean waitingForDefaultUrl = false;
    private boolean waitingForDnsttUrl = false;

    // copied from org.greatfire.envoy.NetworkIntentService.kt, could not be found in imported class
    public static final String BROADCAST_URL_VALIDATION_SUCCEEDED = "org.greatfire.envoy.VALIDATION_SUCCEEDED";
    public static final String BROADCAST_URL_VALIDATION_FAILED = "org.greatfire.envoy.VALIDATION_FAILED";
    public static final String EXTENDED_DATA_VALID_URLS = "org.greatfire.envoy.VALID_URLS";
    public static final String EXTENDED_DATA_INVALID_URLS = "org.greatfire.envoy.INVALID_URLS";

    private Object hysteriaLock = new Object();
    private Object dnsttLock = new Object();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        FDroidApp fdroidApp = (FDroidApp) getApplication();
        fdroidApp.applyPureBlackBackgroundInDarkTheme(this);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        adapter = new MainViewAdapter(this);

        pager = (RecyclerView) findViewById(R.id.main_view_pager);
        pager.setHasFixedSize(true);
        pager.setLayoutManager(new NonScrollingHorizontalLayoutManager(this));
        pager.setAdapter(adapter);

        // Without this, the focus is completely busted on pre 15 devices. Trying to use them
        // without this ends up with each child view showing for a fraction of a second, then
        // reverting back to the "Latest" screen again, in completely non-deterministic ways.
        if (Build.VERSION.SDK_INT <= 15) {
            pager.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }

        // TODO - implement badge for update icon
        // updatesBadge = bottomNavigation.getOrCreateBadge(R.id.updates);
        // updatesBadge.setVisible(false);

        // set up custom navigation bar to allow shifted icons with frames
        ImageView newestView = (ImageView) findViewById((R.id.newest_button));
        ImageView categoryView = (ImageView) findViewById((R.id.category_button));
        ImageView updateView = (ImageView) findViewById((R.id.update_button));
        ImageView nearbyView = (ImageView) findViewById((R.id.nearby_button));
        ImageView settingsView = (ImageView) findViewById((R.id.settings_button));

        newestView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentPageId != 0) {
                    pager.scrollToPosition(0);
                    setNavSelection(0);
                }
            }
        });

        categoryView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentPageId != 1) {
                    pager.scrollToPosition(1);
                    setNavSelection(1);
                }
            }
        });

        updateView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentPageId != 2) {
                    pager.scrollToPosition(2);
                    setNavSelection(2);
                }
            }
        });

        nearbyView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentPageId != 3) {
                    pager.scrollToPosition(3);
                    NearbyViewBinder.updateUsbOtg(MainActivity.this);
                    setNavSelection(3);
                }
            }
        });

        settingsView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentPageId != 4) {
                    pager.scrollToPosition(4);
                    setNavSelection(4);
                }
            }
        });

        IntentFilter updateableAppsFilter = new IntentFilter(AppUpdateStatusManager.BROADCAST_APPSTATUS_LIST_CHANGED);
        updateableAppsFilter.addAction(AppUpdateStatusManager.BROADCAST_APPSTATUS_CHANGED);
        updateableAppsFilter.addAction(AppUpdateStatusManager.BROADCAST_APPSTATUS_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(onUpdateableAppsChanged, updateableAppsFilter);

        // register to receive repo updates
        IntentFilter repoUpdateFilter = new IntentFilter(LOCAL_ACTION_STATUS);
        LocalBroadcastManager.getInstance(this).registerReceiver(onRepoUpdate, repoUpdateFilter);

        // register to receive valid proxy urls
        IntentFilter envoyFilter = new IntentFilter();
        envoyFilter.addAction(BROADCAST_URL_VALIDATION_SUCCEEDED);
        envoyFilter.addAction(BROADCAST_URL_VALIDATION_FAILED);
        envoyFilter.addAction(ShadowsocksService.SHADOWSOCKS_SERVICE_BROADCAST);
        LocalBroadcastManager.getInstance(this).registerReceiver(onUrlsReceived, envoyFilter);

        // delay until after proxy urls have been validated
        // initialRepoUpdateIfRequired();

        Intent intent = getIntent();
        handleSearchOrAppViewIntent(intent);
    }

    private void refreshNavSelection() {
        setNavSelection(currentPageId);
    }

    private void setNavSelection(int position) {

        currentPageId = position;

        ImageView newestView = (ImageView) findViewById(R.id.newest_button);
        ImageView categoryView = (ImageView) findViewById(R.id.category_button);
        ImageView updateView = (ImageView) findViewById(R.id.update_button);
        ImageView nearbyView = (ImageView) findViewById(R.id.nearby_button);
        ImageView settingsView = (ImageView) findViewById(R.id.settings_button);

        TextView newestText = (TextView) findViewById(R.id.newest_text);
        TextView categoryText = (TextView) findViewById(R.id.category_text);
        TextView updateText = (TextView) findViewById(R.id.update_text);
        TextView nearbyText = (TextView) findViewById(R.id.nearby_text);
        TextView settingsText = (TextView) findViewById(R.id.settings_text);

        // clear all current selections
        newestView.setBackground(getDrawable(R.drawable.ic_gf_newest_unfocus));
        newestText.setTextColor(getResources().getColor(R.color.gfdroid_grey_light));
        newestText.setPadding(0, 0, 0, getResources().getDimensionPixelOffset(R.dimen.nav_unfocus_padding));
        categoryView.setBackground(getDrawable(R.drawable.ic_gf_category_unfocus));
        categoryText.setTextColor(getResources().getColor(R.color.gfdroid_grey_light));
        categoryText.setPadding(0, 0, 0, getResources().getDimensionPixelOffset(R.dimen.nav_unfocus_padding));
        updateView.setBackground(getDrawable(R.drawable.ic_gf_update_unfocus));
        updateText.setTextColor(getResources().getColor(R.color.gfdroid_grey_light));
        updateText.setPadding(0, 0, 0, getResources().getDimensionPixelOffset(R.dimen.nav_unfocus_padding));
        nearbyView.setBackground(getDrawable(R.drawable.ic_gf_nearby_unfocus));
        nearbyText.setTextColor(getResources().getColor(R.color.gfdroid_grey_light));
        nearbyText.setPadding(0, 0, 0, getResources().getDimensionPixelOffset(R.dimen.nav_unfocus_padding));
        settingsView.setBackground(getDrawable(R.drawable.ic_gf_settings_unfocus));
        settingsText.setTextColor(getResources().getColor(R.color.gfdroid_grey_light));
        settingsText.setPadding(0, 0, 0, getResources().getDimensionPixelOffset(R.dimen.nav_unfocus_padding));

        switch(currentPageId) {
            case 0:
                newestView.setBackground(getDrawable(R.drawable.ic_gf_newest_focus));
                newestText.setTextColor(getResources().getColor(R.color.color_primary));
                newestText.setPadding(0, 0, 0, getResources().getDimensionPixelOffset(R.dimen.nav_focus_padding));
                break;
            case 1:
                categoryView.setBackground(getDrawable(R.drawable.ic_gf_category_focus));
                categoryText.setTextColor(getResources().getColor(R.color.color_primary));
                categoryText.setPadding(0, 0, 0, getResources().getDimensionPixelOffset(R.dimen.nav_focus_padding));
                break;
            case 2:
                updateView.setBackground(getDrawable(R.drawable.ic_gf_update_focus));
                updateText.setTextColor(getResources().getColor(R.color.color_primary));
                updateText.setPadding(0, 0, 0, getResources().getDimensionPixelOffset(R.dimen.nav_focus_padding));
                break;
            case 3:
                nearbyView.setBackground(getDrawable(R.drawable.ic_gf_nearby_focus));
                nearbyText.setTextColor(getResources().getColor(R.color.color_primary));
                nearbyText.setPadding(0, 0, 0, getResources().getDimensionPixelOffset(R.dimen.nav_focus_padding));
                break;
            case 4:
                settingsView.setBackground(getDrawable(R.drawable.ic_gf_settings_focus));
                settingsText.setTextColor(getResources().getColor(R.color.color_primary));
                settingsText.setPadding(0, 0, 0, getResources().getDimensionPixelOffset(R.dimen.nav_focus_padding));
                break;
            default:
                break;
        }
    }

    private void setSelectedMenuInNav(int menuId) {
        int position = adapter.adapterPositionFromItemId(menuId);
        pager.scrollToPosition(position);
        setNavSelection(position);
    }

    private void initialRepoUpdateIfRequired() {
        if (CronetNetworking.cronetEngine() == null) {
            Log.d(TAG, "initial update, cronet null");
        } else {
            Log.d(TAG, "initial update, envoy active");
        }
        if (Preferences.get().isIndexNeverUpdated() && !UpdateService.isUpdating()) {
            Utils.debugLog(TAG, "We haven't done an update yet. Forcing repo update.");
            UpdateService.updateNow(this);
        } else {
            Utils.debugLog(TAG, "Repo update is already in progress.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // test a direct connection first
        if (waitingForDirectConnection) {
            Log.d(TAG, "already checking direct connection, don't check again");
        } else {
            Log.d(TAG, "check direct connection");
            waitingForDirectConnection = true;
            Thread directConnectionThread = new Thread() {
                @Override
                public void run() {
                    Log.d(TAG, "direct connection thread run");
                    startDirectConnection();
                }
            };
            directConnectionThread.start();
        }

        FDroidApp.checkStartTor(this, Preferences.get());

        if (getIntent().hasExtra(EXTRA_VIEW_UPDATES)) {
            getIntent().removeExtra(EXTRA_VIEW_UPDATES);
            setSelectedMenuInNav(R.id.updates);
        } else if (getIntent().hasExtra(EXTRA_VIEW_NEARBY)) {
            getIntent().removeExtra(EXTRA_VIEW_NEARBY);
            setSelectedMenuInNav(R.id.nearby);
        } else if (getIntent().hasExtra(EXTRA_VIEW_SETTINGS)) {
            getIntent().removeExtra(EXTRA_VIEW_SETTINGS);
            setSelectedMenuInNav(R.id.settings);
        } else {
            refreshNavSelection();
        }

        // AppDetailsActivity and RepoDetailsActivity set different NFC actions, so reset here
        NfcHelper.setAndroidBeam(this, getApplication().getPackageName());
        checkForAddRepoIntent(getIntent());
    }

    private void startDirectConnection() {
        try {
            Log.d(TAG, "test direct connection");
            URL fdroidUrl = new URL(" https://f-droid.org");
            HttpURLConnection directConnection = (HttpURLConnection)fdroidUrl.openConnection();
            directConnection.setRequestMethod("GET");
            directConnection.connect();
            int responseCode = directConnection.getResponseCode();
            if (200 <= responseCode && responseCode <= 299) {
                Log.d(TAG, "direct connection ok, skip envoy: " + responseCode);
                waitingForDirectConnection = false;
                handleEndState(getApplicationContext(), Preferences.ENVOY_STATE_DIRECT);
                return;
            } else {
                Log.d(TAG, "direct connection failed: " + responseCode);
            }
        } catch (MalformedURLException e) {
            Log.e(TAG, "malformed captive url: " + e.getLocalizedMessage());
        } catch (IOException e) {
            Log.e(TAG, "error opening connection: " + e.getLocalizedMessage());
        }

        Log.e(TAG, "unable to connect directly, start envoy");
        waitingForDirectConnection = false;
        startEnvoy();
    }

    private void startEnvoy() {
        // start envoy from onResume to prevent exception from starting a service when out of focus
        if (CronetNetworking.cronetEngine() != null) {
            Log.d(TAG, "cronet already running, don't try to start again");
        } else if (waitingForDefaultUrl || waitingForDnsttUrl) {
            Log.d(TAG, "already processing urls, don't try to start again");
        } else {
            // run envoy setup (fetches and validate urls)
            Log.d(TAG, "begin processing urls to start cronet");
            Preferences.get().setEnvoyState(Preferences.ENVOY_STATE_PENDING);
            waitingForDefaultUrl = true;
            getDefaultUrls();
        }
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);

        // save current page
        outState.putInt(CURRENT_PAGE_ID, currentPageId);
    }

    @Override
    protected void onRestoreInstanceState(final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        // restore current page
        currentPageId = savedInstanceState.getInt(CURRENT_PAGE_ID, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleSearchOrAppViewIntent(intent);

        // This is called here as well as onResume(), because onNewIntent() is not called the first
        // time the activity is created. An alternative option to make sure that the add repo intent
        // is always handled is to call setIntent(intent) here. However, after this good read:
        // http://stackoverflow.com/a/7749347 it seems that adding a repo is not really more
        // important than the original intent which caused the activity to start (even though it
        // could technically have been an add repo intent itself).
        // The end result is that this method will be called twice for one add repo intent. Once
        // here and once in onResume(). However, the method deals with this by ensuring it only
        // handles the same intent once.
        checkForAddRepoIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_STORAGE_ACCESS) {
            TreeUriScannerIntentService.onActivityResult(this, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) { // NOCHECKSTYLE LineLength
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
     * Since any app could send this {@link Intent}, and the search terms are
     * fed into a SQL query, the data must be strictly sanitized to avoid
     * SQL injection attacks.
     */
    private void handleSearchOrAppViewIntent(Intent intent) {
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            performSearch(query);
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
    static String sanitizeSearchTerms(String query) {
        return query.replaceAll("[^\\p{L}\\d_ -]", " ");
    }

    /**
     * Initiates the {@link AppListActivity} with the relevant search terms passed in via the query arg.
     */
    private void performSearch(String query) {
        Intent searchIntent = new Intent(this, AppListActivity.class);
        searchIntent.putExtra(AppListActivity.EXTRA_SEARCH_TERMS, sanitizeSearchTerms(query));
        startActivity(searchIntent);
    }

    private void checkForAddRepoIntent(Intent intent) {
        // Don't handle the intent after coming back to this view (e.g. after hitting the back button)
        // http://stackoverflow.com/a/14820849
        if (!intent.hasExtra(ADD_REPO_INTENT_HANDLED)) {
            intent.putExtra(ADD_REPO_INTENT_HANDLED, true);
            NewRepoConfig parser = new NewRepoConfig(this, intent);
            if (parser.isValidRepo()) {
                if (parser.isFromSwap()) {
                    SwapWorkflowActivity.requestSwap(this, intent.getData());
                } else {
                    Intent clean = new Intent(ACTION_ADD_REPO, intent.getData(), this, ManageReposActivity.class);
                    if (intent.hasExtra(ManageReposActivity.EXTRA_FINISH_AFTER_ADDING_REPO)) {
                        clean.putExtra(ManageReposActivity.EXTRA_FINISH_AFTER_ADDING_REPO,
                                intent.getBooleanExtra(ManageReposActivity.EXTRA_FINISH_AFTER_ADDING_REPO, true));
                    }
                    startActivity(clean);
                }
                finish();
            } else if (parser.getErrorMessage() != null) {
                Toast.makeText(this, parser.getErrorMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    // TODO - implement badge for update icon
    /*
    private void refreshUpdatesBadge(int canUpdateCount) {
        if (canUpdateCount == 0) {
            updatesBadge.setVisible(false);
            updatesBadge.clearNumber();
        } else {
            updatesBadge.setNumber(canUpdateCount);
            updatesBadge.setVisible(true);
        }
    }
    */

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

    /**
     * There are a bunch of reasons why we would get notified about app statuses.
     * The ones we are interested in are those which would result in the "items requiring user interaction"
     * to increase or decrease:
     * * Change in status to:
     * * {@link AppUpdateStatusManager.Status#ReadyToInstall} (Causes the count to go UP by one)
     * * {@link AppUpdateStatusManager.Status#Installed} (Causes the count to go DOWN by one)
     */
    private final BroadcastReceiver onUpdateableAppsChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean updateBadge = false;

            AppUpdateStatusManager manager = AppUpdateStatusManager.getInstance(context);

            String reason = intent.getStringExtra(AppUpdateStatusManager.EXTRA_REASON_FOR_CHANGE);
            switch (intent.getAction()) {
                // Apps which are added/removed from the list due to becoming ready to install or a repo being
                // disabled both cause us to increase/decrease our badge count respectively.
                case AppUpdateStatusManager.BROADCAST_APPSTATUS_LIST_CHANGED:
                    if (AppUpdateStatusManager.REASON_READY_TO_INSTALL.equals(reason) ||
                            AppUpdateStatusManager.REASON_REPO_DISABLED.equals(reason)) {
                        updateBadge = true;
                    }
                    break;

                // Apps which were previously "Ready to install" but have been removed. We need to lower our badge
                // count in response to this.
                case AppUpdateStatusManager.BROADCAST_APPSTATUS_REMOVED:
                    AppUpdateStatus status = intent.getParcelableExtra(AppUpdateStatusManager.EXTRA_STATUS);
                    if (status != null && status.status == AppUpdateStatusManager.Status.ReadyToInstall) {
                        updateBadge = true;
                    }
                    break;
            }

            // Check if we have moved into the ReadyToInstall or Installed state.
            AppUpdateStatus status = manager.get(
                    intent.getStringExtra(DownloaderService.EXTRA_CANONICAL_URL));
            boolean isStatusChange = intent.getBooleanExtra(AppUpdateStatusManager.EXTRA_IS_STATUS_UPDATE, false);
            if (isStatusChange
                    && status != null
                    && (status.status == AppUpdateStatusManager.Status.ReadyToInstall || status.status == AppUpdateStatusManager.Status.Installed)) { // NOCHECKSTYLE LineLength
                updateBadge = true;
            }

            if (updateBadge) {
                int count = 0;
                for (AppUpdateStatus s : manager.getAll()) {
                    if (s.status == AppUpdateStatusManager.Status.ReadyToInstall) {
                        count++;
                    }
                }

                // TODO - implement badge for update icon
                // refreshUpdatesBadge(count);
            }
        }
    };

    private final BroadcastReceiver onRepoUpdate = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TextUtils.isEmpty(action)) {
                Log.d(TAG, "onRepoUpdate but no action");
                return;
            }

            if (!action.equals(LOCAL_ACTION_STATUS)) {
                Log.d(TAG, "onRepoUpdate but wrong action");
                return;
            }

            int resultCode = intent.getIntExtra(EXTRA_STATUS_CODE, -1);

            switch (resultCode) {
                case STATUS_INFO:
                    Log.d(TAG, "got repo update in MainActivity receiver");
                    String message = intent.getStringExtra(EXTRA_MESSAGE);
                    int progress = intent.getIntExtra(EXTRA_PROGRESS, -1);
                    if (progress < 0 || message == null || message.isEmpty()) {
                        Log.d(TAG, "no progress to report");
                    } else if (adapter == null) {
                        Log.d(TAG, "no adapter to report progress");
                    } else {
                        Log.d(TAG, "report progress: " + progress + " / " + message);
                        adapter.handleProgress(message, progress);
                    }
                    break;
                case STATUS_ERROR_GLOBAL:
                case STATUS_ERROR_LOCAL:
                case STATUS_ERROR_LOCAL_SMALL:
                    Log.d(TAG, "got repo error in MainActivity receiver");
                    if (adapter == null) {
                        Log.d(TAG, "no adapter to report error");
                    } else {
                        Log.d(TAG, "report error");
                        adapter.handleError();
                    }
                    break;
                default:
                    Log.d(TAG, "got repo broadcast in MainActivity receiver");
                    break;
            }
        }
    };

    private void getDefaultUrls() {

        if (BuildConfig.DEF_PROXY == null || BuildConfig.DEF_PROXY.isEmpty()) {
            Log.w(TAG, "no default proxy urls were provided");
            handleUrls(new ArrayList<String>());
        } else {
            Log.d(TAG, "found default proxy urls");
            handleUrls(Arrays.asList(BuildConfig.DEF_PROXY.split(",")));
        }
    }

    private void getDnsttUrls() {

        // check for dnstt project properties
        if (BuildConfig.DNSTT_SERVER == null || BuildConfig.DNSTT_SERVER.isEmpty()
                || BuildConfig.DNSTT_KEY == null || BuildConfig.DNSTT_KEY.isEmpty()
                || BuildConfig.DNSTT_PATH == null || BuildConfig.DNSTT_PATH.isEmpty()
                || ((BuildConfig.DOH_URL == null || BuildConfig.DOH_URL.isEmpty())
                && (BuildConfig.DOT_ADDR == null || BuildConfig.DOT_ADDR.isEmpty()))) {
            Log.e(TAG, "dnstt parameters are not defined, cannot fetch metadata with dnstt");
        } else {

            // set time limit for dnstt (dnstt allows a long timeout and retries, may never return)
            // replaces lifecycleScope.launch(Dispatchers.IO) in kotlin code
            Thread dnsttStopThread = new Thread() {
                @Override
                public void run() {
                    Log.d(TAG, "dnstt stop thread run");
                    try {
                        Log.d(TAG, "start timer");
                        waitingForDnstt = true;
                        synchronized (dnsttLock) {
                            dnsttLock.wait(10000L);  // wait 10 seconds
                        }
                        if (waitingForDnstt) {
                            Log.d(TAG, "stop timer, stop dnstt");
                            waitingForDnstt = false;
                            IEnvoyProxy.stopDnstt();
                        } else {
                            Log.d(TAG, "dnstt already complete");
                        }
                    } catch (InterruptedException e) {
                        Log.d(TAG, "dnstt stop thread interrupted: " + e.getLocalizedMessage());
                    }
                }
            };
            dnsttStopThread.start();

            try {
                // provide either DOH or DOT address, and provide an empty string for the other
                Log.d(TAG, "start dnstt proxy");
                long dnsttPort = IEnvoyProxy.startDnstt(
                        BuildConfig.DNSTT_SERVER,
                        BuildConfig.DOH_URL,
                        BuildConfig.DOT_ADDR,
                        BuildConfig.DNSTT_KEY
                );

                Log.d(TAG, "get list of possible urls from dnstt");
                URL url = new URL("http://127.0.0.1:" + dnsttPort + BuildConfig.DNSTT_PATH);
                Log.d(TAG, "open connection");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                try {
                    Log.d(TAG, "set timeout");
                    connection.setConnectTimeout(5000);
                    Log.d(TAG, "connect");
                    connection.connect();
                } catch (SocketTimeoutException e) {
                    Log.e(TAG, "socket timeout when connecting: " + e.getLocalizedMessage());
                } catch (ConnectException e) {
                    Log.e(TAG, "connection error when connecting: " + e.getLocalizedMessage());
                } catch (Exception e) {
                    Log.e(TAG, "unexpected error when connecting: " + e.getLocalizedMessage());
                }

                try {
                    Log.d(TAG, "open input stream");
                    InputStream input = (InputStream) connection.getInputStream();
                    if (input != null) {
                        Log.d(TAG, "parse json and extract possible urls");
                        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
                        StringBuilder builder = new StringBuilder();
                        String line = null;
                        try {
                            while ((line = reader.readLine()) != null) {
                                Log.d(TAG, "read line");
                                builder.append(line + "\n");
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "i/o error when reading stream: " + e.getLocalizedMessage());
                        } finally {
                            try {
                                input.close();
                            } catch (IOException e) {
                                Log.e(TAG, "i/o error when closing stream: " + e.getLocalizedMessage());
                            }
                        }

                        String json = builder.toString();
                        JSONObject envoyObject = new JSONObject(json);
                        JSONArray envoyUrlArray = envoyObject.getJSONArray("envoyUrls");

                        List urlList = new ArrayList<String>();

                        if (envoyUrlArray != null && envoyUrlArray.length() > 0) {
                            for (int i = 0; i < envoyUrlArray.length(); i++){
                                if (defaultUrls.contains(envoyUrlArray.getString(i)) ||
                                        hysteriaUrlRemote.equals(envoyUrlArray.getString(i)) ||
                                        ssUrlRemote.equals(envoyUrlArray.getString(i))) {
                                    Log.d(TAG, "got url from dnstt that has aready been validated");
                                } else {
                                    Log.d(TAG, "got url from dnstt that has not been validated yet");
                                    urlList.add(envoyUrlArray.getString(i));
                                }
                            }
                        } else {
                            Log.w(TAG, "no dnstt proxy urls were found");
                        }

                        // handleUrls() will manage the end state as needed, thread will stop dnstt
                        handleUrls(urlList);
                        // return here to avoid cleanup/end state
                        return;
                    } else {
                        Log.e(TAG, "response contained no json to parse");
                    }
                } catch (SocketTimeoutException e) {
                    Log.e(TAG, "socket timeout error when reading json: " + e.getLocalizedMessage());
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "file error when reading json: " + e.getLocalizedMessage());
                } catch (Exception e) {
                    Log.e(TAG, "unexpected error when reading file: " + e.getLocalizedMessage());
                }
            } catch (MalformedURLException e) {
                Log.e(TAG, "malformed url error when starting dnstt: " + e.getLocalizedMessage());
            } catch (IOException e) {
                Log.e(TAG, "i/o error when starting dnstt: " + e.getLocalizedMessage());
            } catch (Exception e) {
                Log.e(TAG, "unexpected error when starting dnstt: " + e.getLocalizedMessage());
            }

            Log.d(TAG, "stop dnstt proxy");
            waitingForDnstt = false;
            IEnvoyProxy.stopDnstt();
        }

        // if we ended up here, something went wrong
        Log.e(TAG, "dnstt failure, cannot continue");
        handleEndState(getApplicationContext(), null);
    }

    private void handleUrls(List<String> envoyUrls) {

        // clear values stored from past attempts
        hysteriaUrlRemote = "";
        ssUrlRemote = "";

        // check url types
        for (String url : envoyUrls) {
            if (url.startsWith("hysteria://")) {

                // TEMP: current hysteria host uses an ip not a url
                String shortUrl = url.replace("hysteria://", "");

                Log.d(TAG, "found hysteria url");
                hysteriaUrlRemote = shortUrl;
            } else if (url.startsWith("ss://")) {
                Log.d(TAG, "found ss url");
                ssUrlRemote = url;
            } else {
                Log.d(TAG, "found url");
                if (waitingForDefaultUrl) {
                    defaultUrls.add(url);
                } else {
                    dnsttUrls.add(url);
                }
            }
        }

        // check for urls that require services

        // TODO: handle multiple hysteria/shadowsocks urls
        // TODO: kill services if associated urls are not in use

        if (hysteriaUrlRemote != null && !hysteriaUrlRemote.isEmpty()) {
            Log.d(TAG, "hysteria service needed");
            // start hysteria service
            long hysteriaPort = IEnvoyProxy.startHysteria(hysteriaUrlRemote,
                    "uPa1gar4Guce5ooteyiuthie7soqu5Mu",
                    "-----BEGIN CERTIFICATE-----" + "\n" +
            "MIIEzjCCAzagAwIBAgIRAIwE+m2D+1vvzPZaSLj/a7YwDQYJKoZIhvcNAQELBQAw" + "\n" +
            "fzEeMBwGA1UEChMVbWtjZXJ0IGRldmVsb3BtZW50IENBMSowKAYDVQQLDCFzY21A" + "\n" +
            "bTFwcm8ubG9jYWwgKFN0ZXZlbiBNY0RvbmFsZCkxMTAvBgNVBAMMKG1rY2VydCBz" + "\n" +
            "Y21AbTFwcm8ubG9jYWwgKFN0ZXZlbiBNY0RvbmFsZCkwHhcNMjIwMTI3MDE0NTQ5" + "\n" +
            "WhcNMzIwMTI3MDE0NTQ5WjB/MR4wHAYDVQQKExVta2NlcnQgZGV2ZWxvcG1lbnQg" + "\n" +
            "Q0ExKjAoBgNVBAsMIXNjbUBtMXByby5sb2NhbCAoU3RldmVuIE1jRG9uYWxkKTEx" + "\n" +
            "MC8GA1UEAwwobWtjZXJ0IHNjbUBtMXByby5sb2NhbCAoU3RldmVuIE1jRG9uYWxk" + "\n" +
            "KTCCAaIwDQYJKoZIhvcNAQEBBQADggGPADCCAYoCggGBANd+mMC9kQWwH+h++vmS" + "\n" +
            "Kkqv1xebHKncKT/JAAr6lBG/O9T6V0KEZTgMeVU4XG4C2CVPRzbceADSTN36u2k2" + "\n" +
            "+ToGeP6fEc/sz7SD1Uf/Xu6aZCrEuuK8aHchcn2+BgcV5heiKIpQGHVjFzCgez97" + "\n" +
            "wXdcNowerpWP42WK5yj2e3+VKBojHouvSBrTj3EaYAn5nQLiIpi7ZqHmq7NorOhS" + "\n" +
            "ldaCKO6tp8LRQX0X13FL0o8hNJb7gZuSYxt3NzoP0ZCeKfd9La7409u0ZBUuUrWl" + "\n" +
            "k01gPh+6SqrvsqSf3AnpxvlvUfpm1e9LfUZe0S/J1OYOkF2QdQ+wlzHZsYyxZ2uc" + "\n" +
            "kRWLYbqXkF93X3O2H0SkjYKB3PFKcWNeUdt3LJ4lNrisX+R+JTU+4XpGYznnIebF" + "\n" +
            "/Jt/U9aFkenkE3JHyfe9SDedAqUVO9j6XGRFSK5LuoZsXoEqrqY3DXbUZTsZbkZ2" + "\n" +
            "NVtmM+9/bcuBxDgBxUGnvPLRaHO9Y3rkjc+8Qb40iibW8QIDAQABo0UwQzAOBgNV" + "\n" +
            "HQ8BAf8EBAMCAgQwEgYDVR0TAQH/BAgwBgEB/wIBADAdBgNVHQ4EFgQUyaGG2QSl" + "\n" +
            "nr3VsOPd+7EwfxSIQ7UwDQYJKoZIhvcNAQELBQADggGBAA97ah3o5EUwy/LNSkSK" + "\n" +
            "MEYREtZfZp6oz4IjDMCKN9FKtKcqlpbyJVlz/ahIU9/QDqCKcaAJVLmR57fZ/qio" + "\n" +
            "HNQcm1yvA6TlprnwMHNtPO3cxsi1p0D7EofAy0oAcRp3NgTOWpX7zTpd2pNIuDy6" + "\n" +
            "lmP1iBkUxfXorAN+MR1SzEWYQn2k3hcHesrvTzqGZmcVyRDihLWd7bTeixGO5x8w" + "\n" +
            "fNNWTW+Sd6t1vPVR+qBwSLGUKMxoVeenaP8PXn6u5BDzNkwZKQMWQzFlt+DQL61z" + "\n" +
            "6t5OU73CYgJ7XIKvKN+eFOG9lvYglo8LyDJ74QbznVh/Hcwzps7t3QB/S7Q1imue" + "\n" +
            "7n3hINp1GwDgVmFkk0oIG8+s5z54hxCIABgWZsBr2vtGLvn3+xEDgFtRsY9N4PTO" + "\n" +
            "PRHq//BHvTjFt9pwZs5k+EBu9K3I0WZw2PBWhzLiLA7PdkDiDvPw5sJW80vOVo8w" + "\n" +
            "lTIm9+lxj2TaeiqcPaVRBUG7cmIx+iUFPnpttnp8SvRWlQ==" + "\n" +
            "-----END CERTIFICATE-----");

            Log.d(TAG, "hysteria service started");

            // add url for hysteria service
            if (waitingForDefaultUrl) {
                defaultUrls.add(hysteriaUrlLocal + hysteriaPort);
            } else {
                dnsttUrls.add(hysteriaUrlLocal + hysteriaPort);
            }

            waitingForHysteria = true;
        }

        if (ssUrlRemote != null && !ssUrlRemote.isEmpty()) {
            // Notification.Builder in ShadowsocksService.onStartCommand may require api > 7
            Log.d(TAG, "shadowsocks service needed");
            // start shadowsocks service
            Intent shadowsocksIntent = new Intent(this, ShadowsocksService.class);
            // put shadowsocks proxy url here, should look like ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNz@127.0.0.1:1234 (base64 encode user/password)
            shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL", ssUrlRemote);
            ContextCompat.startForegroundService(getApplicationContext(), shadowsocksIntent);
            // add url for shadowsocks service
            if (waitingForDefaultUrl) {
                defaultUrls.add(ssUrlLocal);
            } else {
                dnsttUrls.add(ssUrlLocal);
            }

            waitingForShadowsocks = true;
        }

        if (waitingForDefaultUrl && defaultUrls.isEmpty()) {
            Log.w(TAG, "no default urls to submit, get additional urls with dnstt");
            waitingForDefaultUrl = false;
            waitingForDnsttUrl = true;
            // start asynchronous dnstt task to fetch proxy urls
            // replaces lifecycleScope.launch(Dispatchers.IO) in kotlin code
            Thread dnsttThread = new Thread() {
                @Override
                public void run() {
                    Log.d(TAG, "no defaults, dnstt thread run");
                    getDnsttUrls();
                }
            };
            dnsttThread.start();

        } else if (waitingForDnsttUrl && dnsttUrls.isEmpty()) {
            waitingForDnsttUrl = false;
            Log.w(TAG, "no dnstt urls to submit, cannot continue");
            handleEndState(getApplicationContext(), null);
        } else if (waitingForShadowsocks) {
            Log.d(TAG, "submit urls after starting shadowsocks service");
        } else if (waitingForHysteria) {
            Log.d(TAG, "submit urls after a short delay for starting hysteria");
            // replaces lifecycleScope.launch(Dispatchers.IO) in kotlin code
            Thread hysteriaDelayThread = new Thread() {
                @Override
                public void run() {
                    Log.d(TAG, "hysteria thread (long) run");
                    try {
                        Log.d(TAG, "start delay");
                        synchronized (hysteriaLock) {
                            hysteriaLock.wait(10000L); // wait 10 seconds
                        }
                        Log.d(TAG, "end delay");
                        waitingForHysteria = false;
                        if (waitingForDefaultUrl) {
                            NetworkIntentService.submit(MainActivity.this, defaultUrls);
                        } else {
                            NetworkIntentService.submit(MainActivity.this, dnsttUrls);
                        }
                    } catch (InterruptedException e) {
                        Log.d(TAG, "hysteria thread (long) interrupted: " + e.getLocalizedMessage());
                    }
                }
            };
            hysteriaDelayThread.start();

        } else {
            // submit list of urls to envoy for evaluation
            Log.d(TAG, "no services needed, submit urls immediately");
            if (waitingForDefaultUrl) {
                NetworkIntentService.submit(MainActivity.this, defaultUrls);
            } else {
                NetworkIntentService.submit(MainActivity.this, dnsttUrls);
            }
        }
    }

    // this receiver listens for the results from the NetworkIntentService started below
    // it should receive a result if no valid urls are found but not if the service throws an exception
    private final BroadcastReceiver onUrlsReceived = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onUrlsReceived triggered");
            if (intent != null && context != null) {
                Log.d(TAG, "onUrlsReceived got action: " + intent.getAction());
                if (intent.getAction() == BROADCAST_URL_VALIDATION_SUCCEEDED) {
                    Log.d(TAG, "onUrlsReceived got validation successful");
                    List<String> validUrls = intent.getStringArrayListExtra(EXTENDED_DATA_VALID_URLS);
                    if (waitingForDefaultUrl || waitingForDnsttUrl) {
                        if (validUrls != null && !validUrls.isEmpty()) {
                            Log.d(TAG, "received " + validUrls.size() + " valid urls");
                            // if we get a valid url, it doesn't matter whether it's from defaults or dnstt
                            waitingForDefaultUrl = false;
                            waitingForDnsttUrl = false;
                            // select the fastest one (urls are ordered by latency), reInitializeIfNeeded set to false
                            String envoyUrl = validUrls.get(0);
                            Log.d(TAG, "received first valid url");
                            handleEndState(context, envoyUrl);
                        } else {
                            Log.e(TAG, "received empty list of valid urls");
                        }
                    } else {
                        Log.d(TAG, "received additional valid url");
                    }
                } else if (intent.getAction() == BROADCAST_URL_VALIDATION_FAILED) {
                    Log.d(TAG, "onUrlsReceived got validation failed");
                    List<String> invalidUrls = intent.getStringArrayListExtra(EXTENDED_DATA_INVALID_URLS);
                    if (invalidUrls != null && !invalidUrls.isEmpty()) {
                        if (waitingForDefaultUrl && (invalidUrls.size() >= defaultUrls.size())) {
                            Log.e(TAG, "no default urls left to try, fetch urls with dnstt");
                            waitingForDefaultUrl = false;
                            waitingForDnsttUrl = true;
                            // start asynchronous dnstt task to fetch proxy urls
                            // replaces lifecycleScope.launch(Dispatchers.IO) in kotlin code
                            Thread dnsttThread = new Thread() {
                                @Override
                                public void run() {
                                    Log.d(TAG, "no valid defaults, dnstt thread run");
                                    getDnsttUrls();
                                }
                            };
                            dnsttThread.start();
                        } else if (waitingForDnsttUrl && (invalidUrls.size() >= dnsttUrls.size())) {
                            Log.e(TAG, "no dnstt urls left to try, cannot continue");
                            waitingForDnsttUrl = false;
                            handleEndState(context, null);
                        } else {
                            Log.e(TAG, "still trying urls: default - " + waitingForDefaultUrl + ", " + defaultUrls.size() + " / dnstt - " + waitingForDnsttUrl + ", " + dnsttUrls.size());
                        }
                    } else {
                        Log.e(TAG, "received empty list of invalid urls");
                    }
                } else if (intent.getAction() == ShadowsocksService.SHADOWSOCKS_SERVICE_BROADCAST) {
                    Log.d(TAG, "onUrlsReceived got shadowsocks broadcast");
                    waitingForShadowsocks = false;
                    int shadowsocksResult = intent.getIntExtra(ShadowsocksService.SHADOWSOCKS_SERVICE_RESULT, 0);
                    if (shadowsocksResult > 0) {
                        Log.d(TAG, "shadowsocks service started ok");
                    } else {
                        Log.e(TAG, "shadowsocks service failed to start");
                    }
                    // shadowsocks service was started if possible, submit list of urls to envoy for evaluation
                    if (waitingForHysteria) {
                        Log.d(TAG, "submit urls after an additional delay for starting hysteria");
                        // replaces lifecycleScope.launch(Dispatchers.IO) in kotlin code
                        Thread hysteriaDelayThread = new Thread() {
                            @Override
                            public void run() {
                                Log.d(TAG, "hysteria thread (short) run");
                                try {
                                    Log.d(TAG, "start delay");
                                    synchronized (hysteriaLock) {
                                        hysteriaLock.wait(5000L); // wait 5 seconds
                                    }
                                    Log.d(TAG, "end delay");
                                    waitingForHysteria = false;
                                    if (waitingForDefaultUrl) {
                                        Log.d(TAG, "submit " + defaultUrls.size() + " default urls");
                                        NetworkIntentService.submit(MainActivity.this, defaultUrls);
                                    } else {
                                        Log.d(TAG, "submit " + defaultUrls.size() + " dnstt urls");
                                        NetworkIntentService.submit(MainActivity.this, dnsttUrls);
                                    }
                                } catch (InterruptedException e) {
                                    // TODO: should we submit urls if hysteria thread interrupts?
                                    Log.d(TAG, "hysteria thread (short) interrupted: " + e.getLocalizedMessage());
                                }
                            }
                        };
                        hysteriaDelayThread.start();

                    } else {
                        Log.d(TAG, "submit urls, no additional delay is needed");
                        if (waitingForDefaultUrl) {
                            NetworkIntentService.submit(MainActivity.this, defaultUrls);
                        } else {
                            NetworkIntentService.submit(MainActivity.this, dnsttUrls);
                        }
                    }
                } else {
                    Log.e(TAG, "received unexpected intent: " + intent.getAction());
                }
            } else {
                Log.e(TAG, "receiver triggered but context or intent was null");
            }
        }
    };

    private void handleEndState(Context context, String envoyUrl) {

        // TODO: need to manage envoy url preference more carefully
        if (envoyUrl == null || envoyUrl.isEmpty()) {
            Log.e(TAG, "no valid url could be found, cannot start envoy/cronet, clear saved url");
            Preferences.get().setEnvoyUrl(null);
            Preferences.get().setEnvoyState(Preferences.ENVOY_STATE_FAILED);
        } else if (envoyUrl.equals(Preferences.ENVOY_STATE_DIRECT)) {
            Log.d(TAG, "connecting directly, don't start envoy/cronet, clear saved url");
            Preferences.get().setEnvoyUrl(null);
            Preferences.get().setEnvoyState(Preferences.ENVOY_STATE_DIRECT);
        } else {
            Log.d(TAG, "valid url found, start envoy/cronet, save url");
            CronetNetworking.initializeCronetEngine(context, envoyUrl);
            Preferences.get().setEnvoyUrl(envoyUrl);
            Preferences.get().setEnvoyState(Preferences.ENVOY_STATE_ACTIVE);
        }

        // TODO: need to manage repo update more carefully
        // TODO: confirm whether we should initialize repo even if envoy/cronet is not active
        Log.d(TAG, "do delayed repo update (if needed)");
        initialRepoUpdateIfRequired();

        Log.d(TAG, "give tor another chance to start");
        FDroidApp.checkStartTor(context, Preferences.get());
    }
}