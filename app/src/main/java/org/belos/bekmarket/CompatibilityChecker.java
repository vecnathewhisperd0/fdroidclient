package org.belos.belmarket;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import org.belos.belmarket.compat.SupportedArchitectures;
import org.belos.belmarket.data.Apk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Call getIncompatibleReasons(apk) on an instance of this class to
// find reasons why an apk may be incompatible with the user's device.
public class CompatibilityChecker {

    private static final String TAG = "Compatibility";

    private final Context context;
    private final Set<String> features;
    private final String[] cpuAbis;
    private final String cpuAbisDesc;
    private final boolean ignoreTouchscreen;

    public CompatibilityChecker(Context ctx) {

        context = ctx.getApplicationContext();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        ignoreTouchscreen = prefs.getBoolean(Preferences.PREF_IGN_TOUCH, false);

        PackageManager pm = ctx.getPackageManager();

        features = new HashSet<>();
        if (pm != null) {
            final FeatureInfo[] featureArray = pm.getSystemAvailableFeatures();
            if (featureArray != null) {
                if (BuildConfig.DEBUG) {
                    StringBuilder logMsg = new StringBuilder("Available device features:");
                    for (FeatureInfo fi : pm.getSystemAvailableFeatures()) {
                        logMsg.append('\n').append(fi.name);
                    }
                    Utils.debugLog(TAG, logMsg.toString());
                }
                for (FeatureInfo fi : pm.getSystemAvailableFeatures()) {
                    features.add(fi.name);
                }
            }
        }

        cpuAbis = SupportedArchitectures.getAbis();

        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (final String abi : cpuAbis) {
            if (first) {
                first = false;
            } else {
                builder.append(", ");
            }
            builder.append(abi);
        }
        cpuAbisDesc = builder.toString();
    }

    private boolean compatibleApi(@Nullable String[] nativecode) {
        if (nativecode == null) {
            return true;
        }

        for (final String cpuAbi : cpuAbis) {
            for (String code : nativecode) {
                if (code.equals(cpuAbi)) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<String> getIncompatibleReasons(final Apk apk) {

        List<String> incompatibleReasons = new ArrayList<>();

        if (Build.VERSION.SDK_INT < apk.minSdkVersion) {
            incompatibleReasons.add(context.getString(
                    R.string.minsdk_or_later,
                    Utils.getAndroidVersionName(apk.minSdkVersion)));
        } else if (Build.VERSION.SDK_INT > apk.maxSdkVersion) {
            incompatibleReasons.add(context.getString(
                    R.string.up_to_maxsdk,
                    Utils.getAndroidVersionName(apk.maxSdkVersion)));
        }

        if (apk.features != null) {
            for (final String feat : apk.features) {
                if (ignoreTouchscreen && "android.hardware.touchscreen".equals(feat)) {
                    continue;
                }
                if (!features.contains(feat)) {
                    Collections.addAll(incompatibleReasons, feat.split(","));
                    Utils.debugLog(TAG, apk.packageName + " vercode " + apk.versionCode
                            + " is incompatible based on lack of " + feat);
                }
            }
        }
        if (!compatibleApi(apk.nativecode)) {
            Collections.addAll(incompatibleReasons, apk.nativecode);
            Utils.debugLog(TAG, apk.packageName + " vercode " + apk.versionCode
                    + " only supports " + TextUtils.join(", ", apk.nativecode)
                    + " while your architectures are " + cpuAbisDesc);
        }

        return incompatibleReasons;
    }
}
