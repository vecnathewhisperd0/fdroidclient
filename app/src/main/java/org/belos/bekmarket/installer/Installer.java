/*
 * Copyright (C) 2016 Blue Jay Wireless
 * Copyright (C) 2016 Dominik Schürmann <dominik@dominikschuermann.de>
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

package org.belos.belmarket.installer;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.PatternMatcher;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import org.belos.belmarket.data.Apk;
import org.belos.belmarket.data.ApkProvider;
import org.belos.belmarket.privileged.views.AppDiff;
import org.belos.belmarket.privileged.views.AppSecurityPermissions;
import org.belos.belmarket.privileged.views.InstallConfirmActivity;
import org.belos.belmarket.privileged.views.UninstallDialogActivity;

import java.io.IOException;

/**
 * Handles the actual install process.  Subclasses implement the details.
 */
public abstract class Installer {
    private static final String TAG = "Installer";

    final Context context;
    final Apk apk;

    public static final String ACTION_INSTALL_STARTED = "org.belos.belmarket.installer.Installer.action.INSTALL_STARTED";
    public static final String ACTION_INSTALL_COMPLETE = "org.belos.belmarket.installer.Installer.action.INSTALL_COMPLETE";
    public static final String ACTION_INSTALL_INTERRUPTED = "org.belos.belmarket.installer.Installer.action.INSTALL_INTERRUPTED";
    public static final String ACTION_INSTALL_USER_INTERACTION = "org.belos.belmarket.installer.Installer.action.INSTALL_USER_INTERACTION";

    public static final String ACTION_UNINSTALL_STARTED = "org.belos.belmarket.installer.Installer.action.UNINSTALL_STARTED";
    public static final String ACTION_UNINSTALL_COMPLETE = "org.belos.belmarket.installer.Installer.action.UNINSTALL_COMPLETE";
    public static final String ACTION_UNINSTALL_INTERRUPTED = "org.belos.belmarket.installer.Installer.action.UNINSTALL_INTERRUPTED";
    public static final String ACTION_UNINSTALL_USER_INTERACTION = "org.belos.belmarket.installer.Installer.action.UNINSTALL_USER_INTERACTION";

    /**
     * The URI where the APK was originally downloaded from. This is also used
     * as the unique ID representing this in the whole install process in
     * {@link InstallManagerService}, there is is generally known as the
     * "download URL" since it is the URL used to download the APK.
     *
     * @see Intent#EXTRA_ORIGINATING_URI
     */
    static final String EXTRA_DOWNLOAD_URI = "org.belos.belmarket.installer.Installer.extra.DOWNLOAD_URI";
    public static final String EXTRA_APK = "org.belos.belmarket.installer.Installer.extra.APK";
    public static final String EXTRA_USER_INTERACTION_PI = "org.belos.belmarket.installer.Installer.extra.USER_INTERACTION_PI";
    public static final String EXTRA_ERROR_MESSAGE = "org.belos.belmarket.net.installer.Installer.extra.ERROR_MESSAGE";

    /**
     * @param apk must be included so that all the phases of the install process
     *            can get all the data about the app, even after F-Droid was killed
     */
    Installer(Context context, Apk apk) {
        this.context = context;
        this.apk = apk;
    }

    /**
     * Returns permission screen for given apk.
     *
     * @return Intent with Activity to show required permissions.
     * Returns null if Installer handles that on itself, e.g., with DefaultInstaller,
     * or if no new permissions have been introduced during an update
     */
    public Intent getPermissionScreen() {
        if (!isUnattended()) {
            return null;
        }

        int count = newPermissionCount();
        if (count == 0) {
            // no permission screen needed!
            return null;
        }
        Uri uri = ApkProvider.getApkFromAnyRepoUri(apk);
        Intent intent = new Intent(context, InstallConfirmActivity.class);
        intent.setData(uri);

        return intent;
    }

    private int newPermissionCount() {
        boolean supportsRuntimePermissions = apk.targetSdkVersion >= 23;
        if (supportsRuntimePermissions) {
            return 0;
        }

        AppDiff appDiff = new AppDiff(context.getPackageManager(), apk);
        if (appDiff.pkgInfo == null) {
            // could not get diff because we couldn't parse the package
            throw new RuntimeException("cannot parse!");
        }
        AppSecurityPermissions perms = new AppSecurityPermissions(context, appDiff.pkgInfo);
        if (appDiff.installedAppInfo != null) {
            // update to an existing app
            return perms.getPermissionCount(AppSecurityPermissions.WHICH_NEW);
        }
        // new app install
        return perms.getPermissionCount(AppSecurityPermissions.WHICH_ALL);
    }

    /**
     * Returns an Intent to start a dialog wrapped in an activity
     * for uninstall confirmation.
     *
     * @return Intent with activity for uninstall confirmation
     * Returns null if Installer handles that on itself, e.g.,
     * with DefaultInstaller.
     */
    public Intent getUninstallScreen() {
        if (!isUnattended()) {
            return null;
        }

        Intent intent = new Intent(context, UninstallDialogActivity.class);
        intent.putExtra(Installer.EXTRA_APK, apk);

        return intent;
    }

    void sendBroadcastInstall(Uri downloadUri, String action, PendingIntent pendingIntent) {
        sendBroadcastInstall(context, downloadUri, action, apk, pendingIntent, null);
    }

    void sendBroadcastInstall(Uri downloadUri, String action) {
        sendBroadcastInstall(context, downloadUri, action, apk, null, null);
    }

    void sendBroadcastInstall(Uri downloadUri, String action, String errorMessage) {
        sendBroadcastInstall(context, downloadUri, action, apk, null, errorMessage);
    }

    static void sendBroadcastInstall(Context context,
                                     Uri downloadUri, String action, Apk apk,
                                     PendingIntent pendingIntent, String errorMessage) {
        Intent intent = new Intent(action);
        intent.setData(downloadUri);
        intent.putExtra(Installer.EXTRA_USER_INTERACTION_PI, pendingIntent);
        intent.putExtra(Installer.EXTRA_APK, apk);
        if (!TextUtils.isEmpty(errorMessage)) {
            intent.putExtra(Installer.EXTRA_ERROR_MESSAGE, errorMessage);
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    void sendBroadcastUninstall(String action, String errorMessage) {
        sendBroadcastUninstall(action, null, errorMessage);
    }

    void sendBroadcastUninstall(String action) {
        sendBroadcastUninstall(action, null, null);
    }

    void sendBroadcastUninstall(String action, PendingIntent pendingIntent) {
        sendBroadcastUninstall(action, pendingIntent, null);
    }

    void sendBroadcastUninstall(String action, PendingIntent pendingIntent, String errorMessage) {
        Uri uri = Uri.fromParts("package", apk.packageName, null);

        Intent intent = new Intent(action);
        intent.setData(uri); // for broadcast filtering
        intent.putExtra(Installer.EXTRA_APK, apk);
        intent.putExtra(Installer.EXTRA_USER_INTERACTION_PI, pendingIntent);
        if (!TextUtils.isEmpty(errorMessage)) {
            intent.putExtra(Installer.EXTRA_ERROR_MESSAGE, errorMessage);
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    /**
     * Gets an {@link IntentFilter} for matching events from the install
     * process based on the original download URL as a {@link Uri}.
     */
    public static IntentFilter getInstallIntentFilter(Uri uri) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Installer.ACTION_INSTALL_STARTED);
        intentFilter.addAction(Installer.ACTION_INSTALL_COMPLETE);
        intentFilter.addAction(Installer.ACTION_INSTALL_INTERRUPTED);
        intentFilter.addAction(Installer.ACTION_INSTALL_USER_INTERACTION);
        intentFilter.addDataScheme(uri.getScheme());
        intentFilter.addDataAuthority(uri.getHost(), String.valueOf(uri.getPort()));
        intentFilter.addDataPath(uri.getPath(), PatternMatcher.PATTERN_LITERAL);
        return intentFilter;
    }

    public static IntentFilter getUninstallIntentFilter(String packageName) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Installer.ACTION_UNINSTALL_STARTED);
        intentFilter.addAction(Installer.ACTION_UNINSTALL_COMPLETE);
        intentFilter.addAction(Installer.ACTION_UNINSTALL_INTERRUPTED);
        intentFilter.addAction(Installer.ACTION_UNINSTALL_USER_INTERACTION);
        intentFilter.addDataScheme("package");
        intentFilter.addDataPath(packageName, PatternMatcher.PATTERN_LITERAL);
        return intentFilter;
    }

    /**
     * Install apk
     *
     * @param localApkUri points to the local copy of the APK to be installed
     * @param downloadUri serves as the unique ID for all actions related to the
     *                    installation of that specific APK
     */
    public void installPackage(Uri localApkUri, Uri downloadUri) {
        try {
            // verify that permissions of the apk file match the ones from the apk object
            ApkVerifier apkVerifier = new ApkVerifier(context, localApkUri, apk);
            apkVerifier.verifyApk();
        } catch (ApkVerifier.ApkVerificationException e) {
            Log.e(TAG, e.getMessage(), e);
            sendBroadcastInstall(downloadUri, Installer.ACTION_INSTALL_INTERRUPTED,
                    e.getMessage());
            return;
        } catch (ApkVerifier.ApkPermissionUnequalException e) {
            // if permissions of apk are not the ones listed in the repo
            // and an unattended installer is used, a wrong permission screen
            // has been shown, thus fallback to AOSP DefaultInstaller!
            if (isUnattended()) {
                Log.e(TAG, e.getMessage(), e);
                Log.e(TAG, "Falling back to AOSP DefaultInstaller!");
                DefaultInstaller defaultInstaller = new DefaultInstaller(context, apk);
                defaultInstaller.installPackageInternal(localApkUri, downloadUri);
                return;
            }
        }

        Uri sanitizedUri;
        try {
            // move apk file to private directory for installation and check hash
            sanitizedUri = ApkFileProvider.getSafeUri(
                    context, localApkUri, apk, supportsContentUri());
        } catch (IOException e) {
            Log.e(TAG, e.getMessage(), e);
            sendBroadcastInstall(downloadUri, Installer.ACTION_INSTALL_INTERRUPTED,
                    e.getMessage());
            return;
        }

        installPackageInternal(sanitizedUri, downloadUri);
    }

    protected abstract void installPackageInternal(Uri localApkUri, Uri downloadUri);

    /**
     * Uninstall app as defined by {@link Installer#apk} in
     * {@link Installer#Installer(Context, Apk)}
     */
    protected abstract void uninstallPackage();

    /**
     * This {@link Installer} instance is capable of "unattended" install and
     * uninstall activities, without the system enforcing a user prompt.
     */
    protected abstract boolean isUnattended();

    /**
     * @return true if the Installer supports content Uris and not just file Uris
     */
    protected abstract boolean supportsContentUri();

}
