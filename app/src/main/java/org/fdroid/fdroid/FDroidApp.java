/*
 * Copyright (C) 2010-12  Ciaran Gultnieks, ciaran@ciarang.com
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

package org.belos.belmarket;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.nostra13.universalimageloader.cache.disc.impl.LimitedAgeDiskCache;
import com.nostra13.universalimageloader.cache.disc.naming.FileNameGenerator;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;

import org.acra.ACRA;
import org.acra.ReportingInteractionMode;
import org.acra.annotation.ReportsCrashes;
import org.apache.commons.net.util.SubnetUtils;
import org.belos.belmarket.Preferences.ChangeListener;
import org.belos.belmarket.Preferences.Theme;
import org.belos.belmarket.compat.PRNGFixes;
import org.belos.belmarket.data.AppProvider;
import org.belos.belmarket.data.InstalledAppProviderService;
import org.belos.belmarket.data.Repo;
import org.belos.belmarket.installer.InstallHistoryService;
import org.belos.belmarket.net.IconDownloader;
import org.belos.belmarket.net.WifiStateChangeService;

import java.net.URL;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.security.Security;
import java.util.List;
import java.util.Locale;

import info.guardianproject.netcipher.NetCipher;
import info.guardianproject.netcipher.proxy.OrbotHelper;
import sun.net.www.protocol.bluetooth.Handler;

@ReportsCrashes(mailTo = "reports@f-droid.org",
        mode = ReportingInteractionMode.DIALOG,
        reportDialogClass = org.belos.belmarket.acra.CrashReportActivity.class,
        reportSenderFactoryClasses = org.belos.belmarket.acra.CrashReportSenderFactory.class
)
public class FDroidApp extends Application {

    private static final String TAG = "FDroidApp";

    public static final String SYSTEM_DIR_NAME = Environment.getRootDirectory().getAbsolutePath();

    private static Locale locale;

    // for the local repo on this device, all static since there is only one
    public static volatile int port;
    public static volatile String ipAddressString;
    public static volatile SubnetUtils.SubnetInfo subnetInfo;
    public static volatile String ssid;
    public static volatile String bssid;
    public static volatile Repo repo = new Repo();

    // Leaving the fully qualified class name here to help clarify the difference between spongy/bouncy castle.
    private static final org.spongycastle.jce.provider.BouncyCastleProvider SPONGYCASTLE_PROVIDER;

    @SuppressWarnings("unused")
    BluetoothAdapter bluetoothAdapter;

    static {
        SPONGYCASTLE_PROVIDER = new org.spongycastle.jce.provider.BouncyCastleProvider();
        enableSpongyCastle();
    }

    private static Theme curTheme = Theme.light;

    public void reloadTheme() {
        curTheme = Preferences.get().getTheme();
    }

    public void applyTheme(Activity activity) {
        activity.setTheme(getCurThemeResId());
    }

    public static int getCurThemeResId() {
        switch (curTheme) {
            case light:
                return R.style.AppThemeLight;
            case dark:
                return R.style.AppThemeDark;
            case night:
                return R.style.AppThemeNight;
            default:
                return R.style.AppThemeLight;
        }
    }

    public void applyDialogTheme(Activity activity) {
        activity.setTheme(getCurDialogThemeResId());
    }

    private static int getCurDialogThemeResId() {
        switch (curTheme) {
            case light:
                return R.style.MinWithDialogBaseThemeLight;
            case dark:
                return R.style.MinWithDialogBaseThemeDark;
            case night:
                return R.style.MinWithDialogBaseThemeDark;
            default:
                return R.style.MinWithDialogBaseThemeLight;
        }
    }

    public static void enableSpongyCastle() {
        Security.addProvider(SPONGYCASTLE_PROVIDER);
    }

    public static void enableSpongyCastleOnLollipop() {
        if (Build.VERSION.SDK_INT == 21) {
            Security.addProvider(SPONGYCASTLE_PROVIDER);
        }
    }

    public static void disableSpongyCastleOnLollipop() {
        if (Build.VERSION.SDK_INT == 21) {
            Security.removeProvider(SPONGYCASTLE_PROVIDER.getName());
        }
    }

    /**
     * Initialize the settings needed to run a local swap repo. This should
     * only ever be called in {@link org.belos.belmarket.net.WifiStateChangeService.WifiInfoThread},
     * after the single init call in {@link FDroidApp#onCreate()}.
     */
    public static void initWifiSettings() {
        port = 8888;
        ipAddressString = null;
        subnetInfo = new SubnetUtils("0.0.0.0/32").getInfo();
        ssid = "";
        bssid = "";
        repo = new Repo();
    }

    public void updateLanguage() {
        Context ctx = getBaseContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        String lang = prefs.getString(Preferences.PREF_LANGUAGE, "");
        locale = Utils.getLocaleFromAndroidLangTag(lang);
        applyLanguage();
    }

    private void applyLanguage() {
        Context ctx = getBaseContext();
        Configuration cfg = new Configuration();
        cfg.locale = locale == null ? Locale.getDefault() : locale;
        ctx.getResources().updateConfiguration(cfg, null);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyLanguage();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
        }
        updateLanguage();

        ACRA.init(this);
        if (isAcraProcess()) {
            return;
        }

        PRNGFixes.apply();

        Preferences.setup(this);
        curTheme = Preferences.get().getTheme();
        Preferences.get().configureProxy();

        InstalledAppProviderService.compareToPackageManager(this);

        // If the user changes the preference to do with filtering rooted apps,
        // it is easier to just notify a change in the app provider,
        // so that the newly updated list will correctly filter relevant apps.
        Preferences.get().registerAppsRequiringRootChangeListener(new Preferences.ChangeListener() {
            @Override
            public void onPreferenceChange() {
                getContentResolver().notifyChange(AppProvider.getContentUri(), null);
            }
        });

        // If the user changes the preference to do with filtering anti-feature apps,
        // it is easier to just notify a change in the app provider,
        // so that the newly updated list will correctly filter relevant apps.
        Preferences.get().registerAppsRequiringAntiFeaturesChangeListener(new Preferences.ChangeListener() {
            @Override
            public void onPreferenceChange() {
                getContentResolver().notifyChange(AppProvider.getContentUri(), null);
            }
        });

        // This is added so that the bluetooth:// scheme we use for URLs the BluetoothDownloader
        // understands is not treated as invalid by the java.net.URL class. The actual Handler does
        // nothing, but its presence is enough.
        URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory() {
            @Override
            public URLStreamHandler createURLStreamHandler(String protocol) {
                return TextUtils.equals(protocol, "bluetooth") ? new Handler() : null;
            }
        });

        final Context context = this;
        Preferences.get().registerUnstableUpdatesChangeListener(new Preferences.ChangeListener() {
            @Override
            public void onPreferenceChange() {
                AppProvider.Helper.calcSuggestedApks(context);
            }
        });

        CleanCacheService.schedule(this);

        UpdateService.schedule(getApplicationContext());
        bluetoothAdapter = getBluetoothAdapter();

        ImageLoaderConfiguration config = new ImageLoaderConfiguration.Builder(getApplicationContext())
                .imageDownloader(new IconDownloader(getApplicationContext()))
                .diskCache(new LimitedAgeDiskCache(
                        Utils.getIconsCacheDir(this),
                        null,
                        new FileNameGenerator() {
                            @Override
                            public String generate(String imageUri) {
                                return imageUri.substring(
                                        imageUri.lastIndexOf('/') + 1);
                            }
                        },
                        // 30 days in secs: 30*24*60*60 = 2592000
                        2592000)
                )
                .threadPoolSize(4)
                .threadPriority(Thread.NORM_PRIORITY - 2) // Default is NORM_PRIORITY - 1
                .build();
        ImageLoader.getInstance().init(config);

        FDroidApp.initWifiSettings();
        startService(new Intent(this, WifiStateChangeService.class));
        // if the HTTPS pref changes, then update all affected things
        Preferences.get().registerLocalRepoHttpsListeners(new ChangeListener() {
            @Override
            public void onPreferenceChange() {
                startService(new Intent(FDroidApp.this, WifiStateChangeService.class));
            }
        });

        configureTor(Preferences.get().isTorEnabled());

        if (Preferences.get().isKeepingInstallHistory()) {
            InstallHistoryService.register(this);
        }

        String packageName = getString(R.string.install_history_reader_packageName);
        String unset = getString(R.string.install_history_reader_packageName_UNSET);
        if (!TextUtils.equals(packageName, unset)) {
            int modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if (Build.VERSION.SDK_INT >= 19) {
                modeFlags |= Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
            }
            grantUriPermission(packageName, InstallHistoryService.LOG_URI, modeFlags);
        }
    }

    /**
     * Asks if the current process is "org.belos.belmarket:acra".
     *
     * This is helpful for bailing out of the {@link FDroidApp#onCreate} method early, preventing
     * problems that arise from executing the code twice. This happens due to the `android:process`
     * statement in AndroidManifest.xml causes another process to be created to run
     * {@link org.belos.belmarket.acra.CrashReportActivity}. This was causing lots of things to be
     * started/run twice including {@link CleanCacheService} and {@link WifiStateChangeService}.
     *
     * Note that it is not perfect, because some devices seem to not provide a list of running app
     * processes when asked. In such situations, F-Droid may regress to the behaviour where some
     * services may run twice and thus cause weirdness or slowness. However that is probably better
     * for end users than experiencing a deterministic crash every time F-Droid is started.
     */
    private boolean isAcraProcess() {
        ActivityManager manager = (ActivityManager) this.getSystemService(Context.ACTIVITY_SERVICE);
        List<RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) {
            return false;
        }

        int pid = android.os.Process.myPid();
        for (RunningAppProcessInfo processInfo : processes) {
            if (processInfo.pid == pid && "org.belos.belmarket:acra".equals(processInfo.processName)) {
                return true;
            }
        }

        return false;
    }

    @TargetApi(18)
    private BluetoothAdapter getBluetoothAdapter() {
        // to use the new, recommended way of getting the adapter
        // http://developer.android.com/reference/android/bluetooth/BluetoothAdapter.html
        if (Build.VERSION.SDK_INT < 18) {
            return BluetoothAdapter.getDefaultAdapter();
        }
        return ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
    }

    public void sendViaBluetooth(Activity activity, int resultCode, String packageName) {
        if (resultCode == Activity.RESULT_CANCELED) {
            return;
        }
        String bluetoothPackageName = null;
        String className = null;
        boolean found = false;
        Intent sendBt = null;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName,
                    PackageManager.GET_META_DATA);
            sendBt = new Intent(Intent.ACTION_SEND);
            // The APK type is blocked by stock Android, so use zip
            // sendBt.setType("application/vnd.android.package-archive");
            sendBt.setType("application/zip");
            sendBt.putExtra(Intent.EXTRA_STREAM,
                    Uri.parse("file://" + appInfo.publicSourceDir));
            // not all devices have the same Bluetooth Activities, so
            // let's find it
            for (ResolveInfo info : pm.queryIntentActivities(sendBt, 0)) {
                bluetoothPackageName = info.activityInfo.packageName;
                if ("com.android.bluetooth".equals(bluetoothPackageName)
                        || "com.mediatek.bluetooth".equals(bluetoothPackageName)) {
                    className = info.activityInfo.name;
                    found = true;
                    break;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not get application info to send via bluetooth", e);
            found = false;
        }
        if (sendBt != null) {
            if (found) {
                sendBt.setClassName(bluetoothPackageName, className);
                activity.startActivity(sendBt);
            } else {
                Toast.makeText(this, R.string.bluetooth_activity_not_found,
                        Toast.LENGTH_SHORT).show();
                activity.startActivity(Intent.createChooser(sendBt, getString(R.string.choose_bt_send)));
            }
        }
    }

    private static boolean useTor;

    /**
     * Set the proxy settings based on whether Tor should be enabled or not.
     */
    private static void configureTor(boolean enabled) {
        useTor = enabled;
        if (useTor) {
            NetCipher.useTor();
        } else {
            NetCipher.clearProxy();
        }
    }

    public static void checkStartTor(Context context) {
        if (useTor) {
            OrbotHelper.requestStartTor(context);
        }
    }

    public static boolean isUsingTor() {
        return useTor;
    }
}
