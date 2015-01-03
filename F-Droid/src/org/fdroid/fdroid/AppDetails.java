/*
 * Copyright (C) 2010-12  Ciaran Gultnieks, ciaran@ciarang.com
 * Copyright (C) 2013 Stefan Völkel, bd@bc-bd.org
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.fdroid.fdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.app.ListFragment;
import android.support.v4.app.NavUtils;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBarActivity;
import android.text.Html;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;

import org.fdroid.fdroid.Utils.CommaSeparatedList;
import org.fdroid.fdroid.compat.PackageManagerCompat;
import org.fdroid.fdroid.data.Apk;
import org.fdroid.fdroid.data.ApkProvider;
import org.fdroid.fdroid.data.App;
import org.fdroid.fdroid.data.AppProvider;
import org.fdroid.fdroid.data.InstalledAppProvider;
import org.fdroid.fdroid.data.Repo;
import org.fdroid.fdroid.data.RepoProvider;
import org.fdroid.fdroid.installer.Installer;
import org.fdroid.fdroid.installer.Installer.AndroidNotCompatibleException;
import org.fdroid.fdroid.installer.Installer.InstallerCallback;
import org.fdroid.fdroid.net.ApkDownloader;
import org.fdroid.fdroid.net.Downloader;

import java.io.File;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.List;

interface AppDetailsData {
    public App getApp();
    public AppDetails.ApkListAdapter getApks();
    public Signature getInstalledSignature();
    public String getInstalledSignatureId();
}

/**
 * Interface which allows the apk list fragment to communicate with the activity when
 * a user requests to install/remove an apk by clicking on an item in the list.
 *
 * NOTE: This is <em>not</em> to do with with the sudo/packagemanager/other installer
 * stuff which allows multiple ways to install apps. It is only here to make fragment-
 * activity communication possible.
 */
interface AppInstallListener {
    public void install(final Apk apk);
    public void removeApk(String packageName);
}

public class AppDetails extends ActionBarActivity implements ProgressListener, AppDetailsData, AppInstallListener {

    private static final String TAG = "org.fdroid.fdroid.AppDetails";

    public static final int REQUEST_ENABLE_BLUETOOTH = 2;

    public static final String EXTRA_APPID = "appid";
    public static final String EXTRA_FROM = "from";

    private FDroidApp fdroidApp;
    private ApkListAdapter adapter;
    private ProgressDialog progressDialog;

    private static class ViewHolder {
        TextView version;
        TextView status;
        TextView size;
        TextView api;
        TextView incompatibleReasons;
        TextView buildtype;
        TextView added;
        TextView nativecode;
    }

    // observer to update view when package has been installed/deleted
    AppObserver myAppObserver;

    class AppObserver extends ContentObserver {

        public AppObserver(Handler handler) {
           super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
           onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            onChange();
        }

        public void onChange() {
            if (!reset(app.id)) {
               AppDetails.this.finish();
               return;
            }

            refreshApkList();
            supportInvalidateOptionsMenu();
        }
    }

    class ApkListAdapter extends ArrayAdapter<Apk> {

        private LayoutInflater mInflater = (LayoutInflater) mctx.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);

        public ApkListAdapter(Context context, App app) {
            super(context, 0);
            final List<Apk> apks = ApkProvider.Helper.findByApp(context, app.id);
            for (final Apk apk : apks) {
                if (apk.compatible || Preferences.get().showIncompatibleVersions()) {
                    add(apk);
                }
            }
        }

        private String getInstalledStatus(final Apk apk) {
            // Definitely not installed.
            if (apk.vercode != app.installedVersionCode) {
                return getString(R.string.not_inst);
            }
            // Definitely installed this version.
            if (mInstalledSigID != null && apk.sig != null
                    && apk.sig.equals(mInstalledSigID)) {
                return getString(R.string.inst);
            }
            // Installed the same version, but from someplace else.
            final String installerPkgName = mPm.getInstallerPackageName(app.id);
            if (installerPkgName != null && installerPkgName.length() > 0) {
                final String installerLabel = InstalledAppProvider
                    .getApplicationLabel(mctx, installerPkgName);
                return getString(R.string.inst_known_source, installerLabel);
            }
            return getString(R.string.inst_unknown_source);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            java.text.DateFormat df = DateFormat.getDateFormat(mctx);
            final Apk apk = getItem(position);
            ViewHolder holder;

            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.apklistitem, parent, false);

                holder = new ViewHolder();
                holder.version = (TextView) convertView.findViewById(R.id.version);
                holder.status = (TextView) convertView.findViewById(R.id.status);
                holder.size = (TextView) convertView.findViewById(R.id.size);
                holder.api = (TextView) convertView.findViewById(R.id.api);
                holder.incompatibleReasons = (TextView) convertView.findViewById(R.id.incompatible_reasons);
                holder.buildtype = (TextView) convertView.findViewById(R.id.buildtype);
                holder.added = (TextView) convertView.findViewById(R.id.added);
                holder.nativecode = (TextView) convertView.findViewById(R.id.nativecode);

                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            holder.version.setText(getString(R.string.version)
                    + " " + apk.version
                    + (apk.vercode == app.suggestedVercode ? "  ☆" : ""));

            holder.status.setText(getInstalledStatus(apk));

            if (apk.size > 0) {
                holder.size.setText(Utils.getFriendlySize(apk.size));
                holder.size.setVisibility(View.VISIBLE);
            } else {
                holder.size.setVisibility(View.GONE);
            }

            if (!Preferences.get().expertMode()) {
                holder.api.setVisibility(View.GONE);
            } else if (apk.minSdkVersion > 0 && apk.maxSdkVersion > 0) {
                holder.api.setText(getString(R.string.minsdk_up_to_maxsdk,
                            Utils.getAndroidVersionName(apk.minSdkVersion),
                            Utils.getAndroidVersionName(apk.maxSdkVersion)));
                holder.api.setVisibility(View.VISIBLE);
            } else if (apk.minSdkVersion > 0) {
                holder.api.setText(getString(R.string.minsdk_or_later,
                            Utils.getAndroidVersionName(apk.minSdkVersion)));
                holder.api.setVisibility(View.VISIBLE);
            } else if (apk.maxSdkVersion > 0) {
                holder.api.setText(getString(R.string.up_to_maxsdk,
                            Utils.getAndroidVersionName(apk.maxSdkVersion)));
                holder.api.setVisibility(View.VISIBLE);
            }

            if (apk.srcname != null) {
                holder.buildtype.setText("source");
            } else {
                holder.buildtype.setText("bin");
            }

            if (apk.added != null) {
                holder.added.setText(getString(R.string.added_on,
                            df.format(apk.added)));
                holder.added.setVisibility(View.VISIBLE);
            } else {
                holder.added.setVisibility(View.GONE);
            }

            if (Preferences.get().expertMode() && apk.nativecode != null) {
                holder.nativecode.setText(apk.nativecode.toString().replaceAll(","," "));
                holder.nativecode.setVisibility(View.VISIBLE);
            } else {
                holder.nativecode.setVisibility(View.GONE);
            }

            if (apk.incompatible_reasons != null) {
                holder.incompatibleReasons.setText(
                    getResources().getString(
                        R.string.requires_features,
                        apk.incompatible_reasons.toPrettyString()));
                holder.incompatibleReasons.setVisibility(View.VISIBLE);
            } else {
                holder.incompatibleReasons.setVisibility(View.GONE);
            }

            // Disable it all if it isn't compatible...
            View[] views = {
                convertView,
                holder.version,
                holder.status,
                holder.size,
                holder.api,
                holder.buildtype,
                holder.added,
                holder.nativecode
            };

            for (View v : views) {
                v.setEnabled(apk.compatible);
            }

            return convertView;
        }
    }

    private static final int INSTALL = Menu.FIRST;
    private static final int UNINSTALL = Menu.FIRST + 1;
    private static final int IGNOREALL = Menu.FIRST + 2;
    private static final int IGNORETHIS = Menu.FIRST + 3;
    private static final int WEBSITE = Menu.FIRST + 4;
    private static final int ISSUES = Menu.FIRST + 5;
    private static final int SOURCE = Menu.FIRST + 6;
    private static final int LAUNCH = Menu.FIRST + 7;
    private static final int SHARE = Menu.FIRST + 8;
    private static final int DONATE = Menu.FIRST + 9;
    private static final int BITCOIN = Menu.FIRST + 10;
    private static final int LITECOIN = Menu.FIRST + 11;
    private static final int DOGECOIN = Menu.FIRST + 12;
    private static final int FLATTR = Menu.FIRST + 13;
    private static final int DONATE_URL = Menu.FIRST + 14;
    private static final int SEND_VIA_BLUETOOTH = Menu.FIRST + 15;

    private App app;
    private PackageManager mPm;
    private ApkDownloader downloadHandler;

    private boolean startingIgnoreAll;
    private int startingIgnoreThis;

    private final Context mctx = this;
    private Installer installer;

    /**
     * Stores relevant data that we want to keep track of when destroying the activity
     * with the expectation of it being recreated straight away (e.g. after an
     * orientation change). One of the major things is that we want the download thread
     * to stay active, but for it not to trigger any UI stuff (e.g. progress dialogs)
     * between the activity being destroyed and recreated.
     */
    private static class ConfigurationChangeHelper {

        public ApkDownloader downloader;
        public App app;

        public ConfigurationChangeHelper(ApkDownloader downloader, App app) {
            this.downloader = downloader;
            this.app = app;
        }
    }

    private boolean inProcessOfChangingConfiguration = false;

    /**
     * Attempt to extract the appId from the intent which launched this activity.
     * Various different intents could cause us to show this activity, such as:
     * <ul>
     *     <li>market://details?id=[app_id]</li>
     *     <li>https://f-droid.org/app/[app_id]</li>
     *     <li>fdroid.app:[app_id]</li>
     * </ul>
     * @return May return null, if we couldn't find the appId. In this case, you will
     * probably want to do something drastic like finish the activity and show some
     * feedback to the user (this method will <em>not</em> do that, it will just return
     * null).
     */
    private String getAppIdFromIntent() {
        Intent i = getIntent();
        Uri data = i.getData();
        String appId = null;
        if (data != null) {
            if (data.isHierarchical()) {
                if (data.getHost() != null && data.getHost().equals("details")) {
                    // market://details?id=app.id
                    appId = data.getQueryParameter("id");
                } else {
                    // https://f-droid.org/app/app.id
                    appId = data.getLastPathSegment();
                    if (appId != null && appId.equals("app")) {
                        appId = null;
                    }
                }
            } else {
                // fdroid.app:app.id
                appId = data.getEncodedSchemeSpecificPart();
            }
            Log.d(TAG, "AppDetails launched from link, for '" + appId + "'");
        } else if (!i.hasExtra(EXTRA_APPID)) {
            Log.e(TAG, "No application ID in AppDetails!?");
        } else {
            appId = i.getStringExtra(EXTRA_APPID);
        }
        return appId;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        fdroidApp = ((FDroidApp) getApplication());
        fdroidApp.applyTheme(this);

        super.onCreate(savedInstanceState);

        // Must be called *after* super.onCreate(), as that is where the action bar
        // compat implementation is assigned in the ActionBarActivity base class.
        supportRequestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

        if (getIntent().hasExtra(EXTRA_FROM)) {
            setTitle(getIntent().getStringExtra(EXTRA_FROM));
        }

        mPm = getPackageManager();

        installer = Installer.getActivityInstaller(this, mPm, myInstallerCallback);

        // Get the preferences we're going to use in this Activity...
        ConfigurationChangeHelper previousData = (ConfigurationChangeHelper)getLastCustomNonConfigurationInstance();
        if (previousData != null) {
            Log.d(TAG, "Recreating view after configuration change.");
            downloadHandler = previousData.downloader;
            if (downloadHandler != null) {
                Log.d(TAG, "Download was in progress before the configuration change, so we will start to listen to its events again.");
            }
            app = previousData.app;
            setApp(app);
        } else {
            if (!reset(getAppIdFromIntent())) {
                finish();
                return;
            }
        }

        // Set up the list...
        adapter = new ApkListAdapter(this, app);

        // Wait until all other intialization before doing this, because it will create the
        // fragments, which rely on data from the activity that is set earlier in this method.
        setContentView(R.layout.app_details);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Check for the presence of a view which only exists in the landscape view.
        // This seems to be the preferred way to interrogate the view, rather than
        // to check the orientation. I guess this is because views can be dynamically
        // chosen based on more than just orientation (e.g. large screen sizes).
        View onlyInLandscape = findViewById(R.id.app_summary_container);

        AppDetailsListFragment listFragment =
                (AppDetailsListFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_app_list);
        if (onlyInLandscape == null) {
            listFragment.setupSummaryHeader();
        } else {
            listFragment.removeSummaryHeader();
        }

        // Spinner seems to default to visible on Android 4.0.3 and 4.0.4
        // https://gitlab.com/fdroid/fdroidclient/issues/75
        // Can't put this in onResume(), because that is called on return from asking
        // the user permission to use su (in which case we still want to show the
        // progress indicator after returning from that prompt).
        setSupportProgressBarIndeterminateVisibility(false);

    }

    // The signature of the installed version.
    private Signature mInstalledSignature;
    private String mInstalledSigID;

    @Override
    protected void onResume() {
        super.onResume();

        // register observer to know when install status changes
        myAppObserver = new AppObserver(new Handler());
        getContentResolver().registerContentObserver(
                AppProvider.getContentUri(app.id),
                true,
                myAppObserver);
        if (downloadHandler != null) {
            if (downloadHandler.isComplete()) {
                downloadCompleteInstallApk();
            } else {
                downloadHandler.setProgressListener(this);

                // Show the progress dialog, if for no other reason than to prevent them attempting
                // to download again (i.e. we force them to touch 'cancel' before they can access
                // the rest of the activity).
                Log.d(TAG, "Showing dialog to user after resuming app details view, because a download was previously in progress");
                updateProgressDialog();
            }
        }
    }

    @Override
    protected void onResumeFragments() {
        super.onResumeFragments();
        refreshApkList();
        supportInvalidateOptionsMenu();
    }

    /**
     * Remove progress listener, suppress progress dialog, set downloadHandler to null.
     */
    private void cleanUpFinishedDownload() {
        if (downloadHandler != null) {
            downloadHandler.removeProgressListener();
            removeProgressDialog();
            downloadHandler = null;
        }
    }

    /**
     * Once the download completes successfully, call this method to start the install process
     * with the file that was downloaded.
     */
    private void downloadCompleteInstallApk() {
        if (downloadHandler != null) {
            installApk(downloadHandler.localFile(), downloadHandler.getApk().id);
            cleanUpFinishedDownload();
        }
    }

    @Override
    protected void onPause() {
        if (myAppObserver != null) {
            getContentResolver().unregisterContentObserver(myAppObserver);
        }
        if (app != null && (app.ignoreAllUpdates != startingIgnoreAll
                || app.ignoreThisUpdate != startingIgnoreThis)) {
            Log.d(TAG, "Updating 'ignore updates', as it has changed since we started the activity...");
            setIgnoreUpdates(app.id, app.ignoreAllUpdates, app.ignoreThisUpdate);
        }

        if (downloadHandler != null) {
            downloadHandler.removeProgressListener();
        }

        removeProgressDialog();

        super.onPause();
    }

    public void setIgnoreUpdates(String appId, boolean ignoreAll, int ignoreVersionCode) {

        Uri uri = AppProvider.getContentUri(appId);

        ContentValues values = new ContentValues(2);
        values.put(AppProvider.DataColumns.IGNORE_ALLUPDATES, ignoreAll ? 1 : 0);
        values.put(AppProvider.DataColumns.IGNORE_THISUPDATE, ignoreVersionCode);

        getContentResolver().update(uri, values, null, null);

    }


    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        inProcessOfChangingConfiguration = true;
        return new ConfigurationChangeHelper(downloadHandler, app);
    }

    @Override
    protected void onDestroy() {
        if (downloadHandler != null) {
            if (!inProcessOfChangingConfiguration) {
                downloadHandler.cancel();
                cleanUpFinishedDownload();
            }
        }
        inProcessOfChangingConfiguration = false;
        super.onDestroy();
    }

    private void removeProgressDialog() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    // Reset the display and list contents. Used when entering the activity, and
    // also when something has been installed/uninstalled.
    // Return true if the app was found, false otherwise.
    private boolean reset(String appId) {

        Log.d(TAG, "Getting application details for " + appId);
        App newApp = null;

        if (appId != null && appId.length() > 0) {
            newApp = AppProvider.Helper.findById(getContentResolver(), appId);
        }

        setApp(newApp);

        return this.app != null;
    }

    /**
     * If passed null, this will show a message to the user ("Could not find app ..." or something
     * like that) and then finish the activity.
     */
    private void setApp(App newApp) {

        if (newApp == null) {
            Toast.makeText(this, getString(R.string.no_such_app), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        app = newApp;

        startingIgnoreAll = app.ignoreAllUpdates;
        startingIgnoreThis = app.ignoreThisUpdate;

        // Get the signature of the installed package...
        mInstalledSignature = null;
        mInstalledSigID = null;

        if (app.isInstalled()) {
            PackageManager pm = getPackageManager();
            try {
                PackageInfo pi = pm.getPackageInfo(app.id, PackageManager.GET_SIGNATURES);
                mInstalledSignature = pi.signatures[0];
                Hasher hash = new Hasher("MD5", mInstalledSignature.toCharsString().getBytes());
                mInstalledSigID = hash.getHash();
            } catch (NameNotFoundException e) {
                Log.d(TAG, "Failed to get installed signature");
            } catch (NoSuchAlgorithmException e) {
                Log.d(TAG, "Failed to calculate signature MD5 sum");
                mInstalledSignature = null;
            }
        }
    }

    private void refreshApkList() {
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {

        super.onPrepareOptionsMenu(menu);
        menu.clear();
        if (app == null)
            return true;
        if (app.canAndWantToUpdate()) {
            MenuItemCompat.setShowAsAction(menu.add(
                        Menu.NONE, INSTALL, 0, R.string.menu_upgrade)
                        .setIcon(R.drawable.ic_menu_refresh),
                    MenuItemCompat.SHOW_AS_ACTION_ALWAYS |
                    MenuItemCompat.SHOW_AS_ACTION_WITH_TEXT);
        }

        // Check count > 0 due to incompatible apps resulting in an empty list.
        if (!app.isInstalled() && app.suggestedVercode > 0 &&
                adapter.getCount() > 0) {
            MenuItemCompat.setShowAsAction(menu.add(
                        Menu.NONE, INSTALL, 1, R.string.menu_install)
                        .setIcon(android.R.drawable.ic_menu_add),
                    MenuItemCompat.SHOW_AS_ACTION_ALWAYS |
                    MenuItemCompat.SHOW_AS_ACTION_WITH_TEXT);
        } else if (app.isInstalled()) {
            MenuItemCompat.setShowAsAction(menu.add(
                        Menu.NONE, UNINSTALL, 1, R.string.menu_uninstall)
                        .setIcon(android.R.drawable.ic_menu_delete),
                    MenuItemCompat.SHOW_AS_ACTION_IF_ROOM |
                    MenuItemCompat.SHOW_AS_ACTION_WITH_TEXT);

            if (mPm.getLaunchIntentForPackage(app.id) != null) {
                MenuItemCompat.setShowAsAction(menu.add(
                            Menu.NONE, LAUNCH, 1, R.string.menu_launch)
                            .setIcon(android.R.drawable.ic_media_play),
                        MenuItemCompat.SHOW_AS_ACTION_ALWAYS |
                        MenuItemCompat.SHOW_AS_ACTION_WITH_TEXT);
            }
        }

        MenuItemCompat.setShowAsAction(menu.add(
                    Menu.NONE, SHARE, 1, R.string.menu_share)
                    .setIcon(android.R.drawable.ic_menu_share),
                MenuItemCompat.SHOW_AS_ACTION_IF_ROOM |
                MenuItemCompat.SHOW_AS_ACTION_WITH_TEXT);

        menu.add(Menu.NONE, IGNOREALL, 2, R.string.menu_ignore_all)
                    .setIcon(android.R.drawable.ic_menu_close_clear_cancel)
                    .setCheckable(true)
                    .setChecked(app.ignoreAllUpdates);

        if (app.hasUpdates()) {
            menu.add(Menu.NONE, IGNORETHIS, 2, R.string.menu_ignore_this)
                        .setIcon(android.R.drawable.ic_menu_close_clear_cancel)
                        .setCheckable(true)
                        .setChecked(app.ignoreThisUpdate >= app.suggestedVercode);
        }
        if (app.webURL.length() > 0) {
            menu.add(Menu.NONE, WEBSITE, 3, R.string.menu_website).setIcon(
                    android.R.drawable.ic_menu_view);
        }
        if (app.trackerURL.length() > 0) {
            menu.add(Menu.NONE, ISSUES, 4, R.string.menu_issues).setIcon(
                    android.R.drawable.ic_menu_view);
        }
        if (app.sourceURL.length() > 0) {
            menu.add(Menu.NONE, SOURCE, 5, R.string.menu_source).setIcon(
                    android.R.drawable.ic_menu_view);
        }

        if (app.bitcoinAddr != null || app.litecoinAddr != null ||
                app.dogecoinAddr != null ||
                app.flattrID != null || app.donateURL != null) {
            SubMenu donate = menu.addSubMenu(Menu.NONE, DONATE, 7,
                    R.string.menu_donate).setIcon(
                    android.R.drawable.ic_menu_send);
            if (app.bitcoinAddr != null)
                donate.add(Menu.NONE, BITCOIN, 8, R.string.menu_bitcoin);
            if (app.litecoinAddr != null)
                donate.add(Menu.NONE, LITECOIN, 8, R.string.menu_litecoin);
            if (app.dogecoinAddr != null)
                donate.add(Menu.NONE, DOGECOIN, 8, R.string.menu_dogecoin);
            if (app.flattrID != null)
                donate.add(Menu.NONE, FLATTR, 9, R.string.menu_flattr);
            if (app.donateURL != null)
                donate.add(Menu.NONE, DONATE_URL, 10, R.string.menu_website);
        }
        if (app.isInstalled() && fdroidApp.bluetoothAdapter != null) { // ignore on devices without Bluetooth
            menu.add(Menu.NONE, SEND_VIA_BLUETOOTH, 6, R.string.send_via_bluetooth);
        }

        return true;
    }


    public void tryOpenUri(String s) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(s));
        if (intent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this,
                    getString(R.string.no_handler_app, intent.getDataString()),
                    Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(intent);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {

        case android.R.id.home:
            NavUtils.navigateUpFromSameTask(this);
            return true;

        case LAUNCH:
            launchApk(app.id);
            return true;

        case SHARE:
            shareApp(app);
            return true;

        case INSTALL:
            // Note that this handles updating as well as installing.
            if (app.suggestedVercode > 0) {
                final Apk apkToInstall = ApkProvider.Helper.find(this, app.id, app.suggestedVercode);
                install(apkToInstall);
            }
            return true;

        case UNINSTALL:
            removeApk(app.id);
            return true;

        case IGNOREALL:
            app.ignoreAllUpdates ^= true;
            item.setChecked(app.ignoreAllUpdates);
            return true;

        case IGNORETHIS:
            if (app.ignoreThisUpdate >= app.suggestedVercode)
                app.ignoreThisUpdate = 0;
            else
                app.ignoreThisUpdate = app.suggestedVercode;
            item.setChecked(app.ignoreThisUpdate > 0);
            return true;

        case WEBSITE:
            tryOpenUri(app.webURL);
            return true;

        case ISSUES:
            tryOpenUri(app.trackerURL);
            return true;

        case SOURCE:
            tryOpenUri(app.sourceURL);
            return true;

        case BITCOIN:
            tryOpenUri("bitcoin:" + app.bitcoinAddr);
            return true;

        case LITECOIN:
            tryOpenUri("litecoin:" + app.litecoinAddr);
            return true;

        case DOGECOIN:
            tryOpenUri("dogecoin:" + app.dogecoinAddr);
            return true;

        case FLATTR:
            tryOpenUri("https://flattr.com/thing/" + app.flattrID);
            return true;

        case DONATE_URL:
            tryOpenUri(app.donateURL);
            return true;

        case SEND_VIA_BLUETOOTH:
            /*
             * If Bluetooth has not been enabled/turned on, then
             * enabling device discoverability will automatically enable Bluetooth
             */
            Intent discoverBt = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverBt.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 121);
            startActivityForResult(discoverBt, REQUEST_ENABLE_BLUETOOTH);
            // if this is successful, the Bluetooth transfer is started
            return true;

        }
        return super.onOptionsItemSelected(item);
    }

    // Install the version of this app denoted by 'app.curApk'.
    @Override
    public void install(final Apk apk) {
        String [] projection = { RepoProvider.DataColumns.ADDRESS };
        Repo repo = RepoProvider.Helper.findById(this, apk.repo, projection);
        if (repo == null || repo.address == null) {
            return;
        }
        final String repoaddress = repo.address;

        if (!apk.compatible) {
            AlertDialog.Builder ask_alrt = new AlertDialog.Builder(this);
            ask_alrt.setMessage(getString(R.string.installIncompatible));
            ask_alrt.setPositiveButton(getString(R.string.yes),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog,
                            int whichButton) {
                            startDownload(apk, repoaddress);
                        }
                    });
            ask_alrt.setNegativeButton(getString(R.string.no),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog,
                                int whichButton) {
                        }
                    });
            AlertDialog alert = ask_alrt.create();
            alert.show();
            return;
        }
        if (mInstalledSigID != null && apk.sig != null
                && !apk.sig.equals(mInstalledSigID)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.SignatureMismatch).setPositiveButton(
                    getString(R.string.ok),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                        }
                    });
            AlertDialog alert = builder.create();
            alert.show();
            return;
        }
        startDownload(apk, repoaddress);
    }

    private void startDownload(Apk apk, String repoAddress) {
        downloadHandler = new ApkDownloader(apk, repoAddress, Utils.getApkCacheDir(getBaseContext()));
        downloadHandler.setProgressListener(this);
        if (downloadHandler.download()) {
            updateProgressDialog();
        }
    }

    private void installApk(File file, String packageName) {
        setSupportProgressBarIndeterminateVisibility(true);

        try {
            installer.installPackage(file);
        } catch (AndroidNotCompatibleException e) {
            Log.e(TAG, "Android not compatible with this Installer!", e);
            setSupportProgressBarIndeterminateVisibility(false);
        }
    }

    @Override
    public void removeApk(String packageName) {
        setSupportProgressBarIndeterminateVisibility(true);

        try {
            installer.deletePackage(packageName);
        } catch (AndroidNotCompatibleException e) {
            Log.e(TAG, "Android not compatible with this Installer!", e);
            setSupportProgressBarIndeterminateVisibility(false);
        }
    }

    Installer.InstallerCallback myInstallerCallback = new Installer.InstallerCallback() {

        @Override
        public void onSuccess(final int operation) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (operation == Installer.InstallerCallback.OPERATION_INSTALL) {
                        PackageManagerCompat.setInstaller(mPm, app.id);
                    }

                    setSupportProgressBarIndeterminateVisibility(false);
                    myAppObserver.onChange();
                }
            });
        }

        @Override
        public void onError(int operation, final int errorCode) {
            if (errorCode == InstallerCallback.ERROR_CODE_CANCELED) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setSupportProgressBarIndeterminateVisibility(false);
                        myAppObserver.onChange();
                    }
                });
            } else {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setSupportProgressBarIndeterminateVisibility(false);
                        myAppObserver.onChange();

                        Log.e(TAG, "Installer aborted with errorCode: " + errorCode);

                        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(AppDetails.this);
                        alertBuilder.setTitle(R.string.installer_error_title);
                        alertBuilder.setMessage(R.string.installer_error_title);
                        alertBuilder.setNeutralButton(android.R.string.ok, null);
                        alertBuilder.create().show();
                    }
                });
            }
        }
    };

    private void launchApk(String id) {
        Intent intent = mPm.getLaunchIntentForPackage(id);
        startActivity(intent);
    }

    private void shareApp(App app) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");

        shareIntent.putExtra(Intent.EXTRA_SUBJECT, app.name);
        shareIntent.putExtra(Intent.EXTRA_TEXT, app.name + " (" + app.summary + ") - https://f-droid.org/app/" + app.id);

        startActivity(Intent.createChooser(shareIntent, getString(R.string.menu_share)));
    }

    private ProgressDialog getProgressDialog(String file) {
        if (progressDialog == null) {
            final ProgressDialog pd = new ProgressDialog(this);
            pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            pd.setMessage(getString(R.string.download_server) + ":\n " + file);
            pd.setCancelable(true);
            pd.setCanceledOnTouchOutside(false);

            // The indeterminate-ness will get overridden on the first progress event we receive.
            pd.setIndeterminate(true);

            pd.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    Log.d(TAG, "User clicked 'cancel' on download, attempting to interrupt download thread.");
                    if (downloadHandler !=  null) {
                        downloadHandler.cancel();
                        cleanUpFinishedDownload();
                    } else {
                        Log.e(TAG, "Tried to cancel, but the downloadHandler doesn't exist.");
                    }
                    progressDialog = null;
                    Toast.makeText(AppDetails.this, getString(R.string.download_cancelled), Toast.LENGTH_LONG).show();
                }
            });
            pd.setButton(DialogInterface.BUTTON_NEUTRAL,
                    getString(R.string.cancel),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            pd.cancel();
                        }
                    }
            );
            progressDialog = pd;
        }
        return progressDialog;
    }

    /**
     * Looks at the current <code>downloadHandler</code> and finds it's size and progress.
     * This is in comparison to {@link org.fdroid.fdroid.AppDetails#updateProgressDialog(int, int)},
     * which is used when you have the details from a freshly received
     * {@link org.fdroid.fdroid.ProgressListener.Event}.
     */
    private void updateProgressDialog() {
        if (downloadHandler != null) {
            updateProgressDialog(downloadHandler.getProgress(), downloadHandler.getTotalSize());
        }
    }

    private void updateProgressDialog(int progress, int total) {
        if (downloadHandler != null) {
            ProgressDialog pd = getProgressDialog(downloadHandler.getRemoteAddress());
            if (total > 0) {
                pd.setIndeterminate(false);
                pd.setProgress(progress);
                pd.setMax(total);
            } else {
                pd.setIndeterminate(true);
                pd.setProgress(progress);
                pd.setMax(0);
            }
            if (!pd.isShowing()) {
                Log.d(TAG, "Showing progress dialog for download.");
                pd.show();
            }
        }
    }

    @Override
    public void onProgress(Event event) {
        if (downloadHandler == null || !downloadHandler.isEventFromThis(event)) {
            // Choose not to respond to events from previous downloaders.
            // We don't even care if we receive "cancelled" events or the like, because
            // we dealt with cancellations in the onCancel listener of the dialog,
            // rather than waiting to receive the event here. We try and be careful in
            // the download thread to make sure that we check for cancellations before
            // sending events, but it is not possible to be perfect, because the interruption
            // which triggers the download can happen after the check to see if
            Log.d(TAG, "Discarding downloader event \"" + event.type + "\" as it is from an old (probably cancelled) downloader.");
            return;
        }

        boolean finished = false;
        if (event.type.equals(Downloader.EVENT_PROGRESS)) {
            updateProgressDialog(event.progress, event.total);
        } else if (event.type.equals(ApkDownloader.EVENT_ERROR)) {
            final String text;
            if (event.getData().getInt(ApkDownloader.EVENT_DATA_ERROR_TYPE) == ApkDownloader.ERROR_HASH_MISMATCH)
                text = getString(R.string.corrupt_download);
            else
                text = getString(R.string.details_notinstalled);
            // this must be on the main UI thread
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            finished = true;
        } else if (event.type.equals(ApkDownloader.EVENT_APK_DOWNLOAD_COMPLETE)) {
            downloadCompleteInstallApk();
            finished = true;
        }

        if (finished) {
            removeProgressDialog();
            downloadHandler = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // handle cases for install manager first
        if (installer.handleOnActivityResult(requestCode, resultCode, data)) {
            return;
        }

        switch (requestCode) {
        case REQUEST_ENABLE_BLUETOOTH:
            fdroidApp.sendViaBluetooth(this, resultCode, app.id);
            break;
        }
    }

    @Override
    public App getApp() {
        return app;
    }

    @Override
    public ApkListAdapter getApks() {
        return adapter;
    }

    @Override
    public Signature getInstalledSignature() {
        return mInstalledSignature;
    }

    @Override
    public String getInstalledSignatureId() {
        return mInstalledSigID;
    }

    public static class AppDetailsSummaryFragment extends Fragment {

        protected final Preferences prefs;
        private AppDetailsData data;

        public AppDetailsSummaryFragment() {
            prefs = Preferences.get();
        }

        @Override
        public void onAttach(Activity activity) {
            super.onAttach(activity);
            data = (AppDetailsData)activity;
        }

        protected App getApp() {
            return data.getApp();
        }

        protected ApkListAdapter getApks() {
            return data.getApks();
        }

        protected Signature getInstalledSignature() {
            return data.getInstalledSignature();
        }

        protected String getInstalledSignatureId() {
            return data.getInstalledSignatureId();
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            super.onCreateView(inflater, container, savedInstanceState);
            View summaryView = inflater.inflate(R.layout.app_details_summary, container, false);
            setupView(summaryView);
            return summaryView;
        }

        @Override
        public void onResume() {
            super.onResume();
            updateViews(getView());
        }

        private void setupView(View view) {

            TextView description = (TextView) view.findViewById(R.id.description);
            Spanned desc = Html.fromHtml(getApp().description, null, new Utils.HtmlTagHandler());
            description.setMovementMethod(LinkMovementMethod.getInstance());
            description.setText(desc.subSequence(0, desc.length() - 2));

            TextView appIdView = (TextView) view.findViewById(R.id.appid);
            if (prefs.expertMode())
                appIdView.setText(getApp().id);
            else
                appIdView.setVisibility(View.GONE);

            TextView summaryView = (TextView) view.findViewById(R.id.summary);
            summaryView.setText(getApp().summary);

            Apk curApk = null;
            for (int i = 0; i < getApks().getCount(); i ++) {
                final Apk apk = getApks().getItem(i);
                if (apk.vercode == getApp().suggestedVercode) {
                    curApk = apk;
                    break;
                }
            }

            TextView permissionListView = (TextView) view.findViewById(R.id.permissions_list);
            TextView permissionHeader = (TextView) view.findViewById(R.id.permissions);
            boolean curApkCompatible = curApk != null && curApk.compatible;
            if (prefs.showPermissions() && !getApks().isEmpty() &&
                    (curApkCompatible || prefs.showIncompatibleVersions())) {

                CommaSeparatedList permsList = getApks().getItem(0).permissions;
                if (permsList == null) {
                    permissionListView.setText(getString(R.string.no_permissions));
                } else {
                    Iterator<String> permissions = permsList.iterator();
                    StringBuilder sb = new StringBuilder();
                    while (permissions.hasNext()) {
                        final String permissionName = permissions.next();
                        try {
                            Permission permission = new Permission(getActivity(), permissionName);
                            // TODO: Make this list RTL friendly
                            sb.append("\t• ").append(permission.getName()).append('\n');
                        } catch (NameNotFoundException e) {
                            if (permissionName.equals("ACCESS_SUPERUSER")) {
                                // TODO: i18n this string, but surely it is already translated somewhere?
                                sb.append("\t• Full permissions to all device features and storage\n");
                            } else {
                                Log.e(TAG, "Permission not yet available: " + permissionName);
                            }
                        }
                    }
                    if (sb.length() > 0) sb.setLength(sb.length() - 1);
                    permissionListView.setText(sb.toString());
                }
                permissionHeader.setText(getString(R.string.permissions_for_long, getApks().getItem(0).version));
            } else {
                permissionListView.setVisibility(View.GONE);
                permissionHeader.setVisibility(View.GONE);
            }

            TextView antiFeaturesView = (TextView) view.findViewById(R.id.antifeatures);
            if (getApp().antiFeatures != null) {
                StringBuilder sb = new StringBuilder();
                for (String af : getApp().antiFeatures) {
                    final String afdesc = descAntiFeature(af);
                    if (afdesc != null) {
                        sb.append("\t• ").append(afdesc).append("\n");
                    }
                }
                if (sb.length() > 0) {
                    sb.setLength(sb.length() - 1);
                    antiFeaturesView.setText(sb.toString());
                } else {
                    antiFeaturesView.setVisibility(View.GONE);
                }
            } else {
                antiFeaturesView.setVisibility(View.GONE);
            }

            updateViews(view);
        }

        private String descAntiFeature(String af) {
            if (af.equals("Ads"))
                return getString(R.string.antiadslist);
            if (af.equals("Tracking"))
                return getString(R.string.antitracklist);
            if (af.equals("NonFreeNet"))
                return getString(R.string.antinonfreenetlist);
            if (af.equals("NonFreeAdd"))
                return getString(R.string.antinonfreeadlist);
            if (af.equals("NonFreeDep"))
                return getString(R.string.antinonfreedeplist);
            if (af.equals("UpstreamNonFree"))
                return getString(R.string.antiupstreamnonfreelist);
            return null;
        }

        public void updateViews(View view) {

            if (view == null) {
                Log.e(TAG, "AppDetailsSummaryFragment.updateViews(): view == null. Oops.");
                return;
            }

            TextView signatureView = (TextView) view.findViewById(R.id.signature);
            if (prefs.expertMode() && getInstalledSignature() != null) {
                signatureView.setVisibility(View.VISIBLE);
                signatureView.setText("Signed: " + getInstalledSignatureId());
            } else {
                signatureView.setVisibility(View.GONE);
            }

        }
    }

    public static class AppDetailsHeaderFragment extends Fragment {

        private AppDetailsData data;
        protected final Preferences prefs;
        protected final DisplayImageOptions displayImageOptions;

        public AppDetailsHeaderFragment() {
            prefs = Preferences.get();
            displayImageOptions = new DisplayImageOptions.Builder()
                .cacheInMemory(true)
                .cacheOnDisk(true)
                .imageScaleType(ImageScaleType.NONE)
                .showImageOnLoading(R.drawable.ic_repo_app_default)
                .showImageForEmptyUri(R.drawable.ic_repo_app_default)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .build();
        }

        private App getApp() {
            return data.getApp();
        }

        private ApkListAdapter getApks() {
            return data.getApks();
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.app_details_header, container, false);
            setupView(view);
            return view;
        }

        @Override
        public void onAttach(Activity activity) {
            super.onAttach(activity);
            data = (AppDetailsData)activity;
        }

        private void setupView(View view) {

            // Set the icon...
            ImageView iv = (ImageView) view.findViewById(R.id.icon);
            ImageLoader.getInstance().displayImage(getApp().iconUrl, iv, displayImageOptions);

            // Set the title and other header details...
            TextView tv = (TextView) view.findViewById(R.id.title);
            tv.setText(getApp().name);
            tv = (TextView) view.findViewById(R.id.license);
            tv.setText(getApp().license);

            if (getApp().categories != null) {
                tv = (TextView) view.findViewById(R.id.categories);
                tv.setText(getApp().categories.toString().replaceAll(",", ", "));
            }

            updateViews(view);
        }

        @Override
        public void onResume() {
            super.onResume();
            updateViews(getView());
        }

        public void updateViews(View view) {

            TextView statusView = (TextView) view.findViewById(R.id.status);
            if (getApp().isInstalled()) {
                statusView.setText(getString(R.string.details_installed, getApp().installedVersionName));
                NfcHelper.setAndroidBeam(getActivity(), getApp().id);
            } else {
                statusView.setText(getString(R.string.details_notinstalled));
                NfcHelper.disableAndroidBeam(getActivity());
            }

        }

    }

    public static class AppDetailsListFragment extends ListFragment {

        private final String SUMMARY_TAG = "summary";

        private AppDetailsData data;
        private AppInstallListener installListener;
        private AppDetailsSummaryFragment summaryFragment = null;

        private FrameLayout headerView;

        @Override
        public void onAttach(Activity activity) {
            super.onAttach(activity);
            data = (AppDetailsData)activity;
            installListener = (AppInstallListener)activity;
        }

        protected void install(final Apk apk) {
            installListener.install(apk);
        }

        protected void remove() {
            installListener.removeApk(getApp().id);
        }

        protected App getApp() {
            return data.getApp();
        }

        protected ApkListAdapter getApks() {
            return data.getApks();
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            // A bit of a hack, but we can't add the header view in setupSummaryHeader(),
            // due to the fact it needs to happen before setListAdapter(). Also, seeing
            // as we may never add a summary header (i.e. in landscape), this is probably
            // the last opportunity to set the list adapter. As such, we use the headerView
            // as a mechanism to optionally allow adding a header in the future.
            if (headerView == null) {
                headerView = new FrameLayout(getActivity().getApplicationContext());
                headerView.setId(R.id.appDetailsSummaryHeader);
            } else {
                Fragment summaryFragment = getChildFragmentManager().findFragmentByTag(SUMMARY_TAG);
                if (summaryFragment != null) {
                    getChildFragmentManager().beginTransaction().remove(summaryFragment).commit();
                }
            }

            setListAdapter(null);
            getListView().addHeaderView(headerView);
            setListAdapter(getApks());
        }

        @Override
        public void onResume() {
            super.onResume();
        }

        @Override
        public void onListItemClick(ListView l, View v, int position, long id) {
            final Apk apk = getApks().getItem(position - l.getHeaderViewsCount());
            if (getApp().installedVersionCode == apk.vercode)
                remove();
            else if (getApp().installedVersionCode > apk.vercode) {
                AlertDialog.Builder ask_alrt = new AlertDialog.Builder(getActivity());
                ask_alrt.setMessage(getString(R.string.installDowngrade));
                ask_alrt.setPositiveButton(getString(R.string.yes),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog,
                                    int whichButton) {
                                install(apk);
                            }
                        });
                ask_alrt.setNegativeButton(getString(R.string.no),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog,
                                    int whichButton) {
                            }
                        });
                AlertDialog alert = ask_alrt.create();
                alert.show();
            } else
                install(apk);
        }

        public void removeSummaryHeader() {
            Fragment summary = getChildFragmentManager().findFragmentByTag(SUMMARY_TAG);
            if (summary != null) {
                getChildFragmentManager().beginTransaction().remove(summary).commit();
                headerView.removeAllViews();
                headerView.setVisibility(View.GONE);
                summaryFragment = null;
            }
        }

        public void setupSummaryHeader() {
            Fragment fragment = getChildFragmentManager().findFragmentByTag(SUMMARY_TAG);
            if (fragment != null) {
                summaryFragment = (AppDetailsSummaryFragment)fragment;
            } else {
                summaryFragment = new AppDetailsSummaryFragment();
            }
            getChildFragmentManager().beginTransaction().replace(headerView.getId(), summaryFragment, SUMMARY_TAG).commit();
            headerView.setVisibility(View.VISIBLE);
        }
    }

}
