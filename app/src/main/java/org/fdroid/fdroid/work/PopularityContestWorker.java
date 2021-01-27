/*
 * Copyright (C) 2021  Hans-Christoph Steiner <hans@eds.org>
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

package org.fdroid.fdroid.work;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.format.DateUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.io.FileUtils;
import org.fdroid.fdroid.BuildConfig;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.data.App;
import org.fdroid.fdroid.data.InstalledAppProvider;
import org.fdroid.fdroid.installer.InstallHistoryService;
import org.fdroid.fdroid.installer.Installer;
import org.fdroid.fdroid.net.HttpPoster;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * This uses static methods so that they can easily be tested in Robolectric
 * rather than painful, slow, flaky emulator tests.
 */
public class PopularityContestWorker extends Worker {

    public static final String TAG = "PopularityContestWorker";

    static SimpleDateFormat weekFormatter = new SimpleDateFormat("yyyy ww", Locale.ENGLISH);

    private static long startTime;

    public PopularityContestWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    /**
     * Schedule or cancel a work request to update the app index, according to the
     * current preferences.  It is meant to run weekly, so it will schedule one week
     * from the last run.  If it has never been run, it will run as soon as possible.
     * <p>
     * Although {@link Constraints.Builder#setRequiresDeviceIdle(boolean)} is available
     * down to {@link Build.VERSION_CODES#M}, it will cause {@code UpdateService} to
     * rarely run, if ever on some devices.  So {@link Constraints.Builder#setRequiresDeviceIdle(boolean)}
     * should only be used in conjunction with
     * {@link Constraints.Builder#setTriggerContentMaxDelay(long, TimeUnit)} to ensure
     * that updates actually happen regularly.
     */
    public static void schedule(@NonNull final Context context) {
        final WorkManager workManager = WorkManager.getInstance(context);
        long interval = TimeUnit.DAYS.toMillis(7);
        // TODO shared preference to store last run time

        final Constraints.Builder constraintsBuilder = new Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true);
        // TODO use the Data/WiFi preferences here
        if (Build.VERSION.SDK_INT >= 24) {
            constraintsBuilder.setTriggerContentMaxDelay(interval, TimeUnit.MILLISECONDS);
            constraintsBuilder.setRequiresDeviceIdle(true);
        }
        final PeriodicWorkRequest cleanCache =
                new PeriodicWorkRequest.Builder(PopularityContestWorker.class, interval, TimeUnit.MILLISECONDS)
                        .setConstraints(constraintsBuilder.build())
                        .build();
        workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.REPLACE, cleanCache);
        Utils.debugLog(TAG, "Scheduled periodic work");
    }

    @NonNull
    @Override
    public Result doWork() {
        startTime = System.currentTimeMillis();
        // TODO use clean insights SDK to submit data
        // TODO check useTor preference and force-submit over Tor.
        if (Build.VERSION.SDK_INT >= 24 && BuildConfig.DEBUG) {
            for (Uri uri : getTriggeredContentUris()) {
                Log.i(TAG, "TriggeredContentUri: " + uri);
            }
        }

        String json = generateReport(getApplicationContext());
        try {
            HttpPoster httpPoster = new HttpPoster(" https://metrics.cleaninsights.org/cleaninsights.php");
            httpPoster.post(json);
            return ListenableWorker.Result.success();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return ListenableWorker.Result.retry();
    }

    /**
     * Convert a Java timestamp in milliseconds to a CleanInsights/Matomo timestamp
     * normalized to the week and in UNIX epoch seconds format.
     */
    static long toCleanInsightsTimestamp(long timestamp) {
        return toCleanInsightsTimestamp(timestamp, timestamp);
    }

    /**
     * Convert a Java timestamp in milliseconds to a CleanInsights/Matomo timestamp
     * normalized to the week and in UNIX epoch seconds format, plus the time
     * difference between {@code relativeTo} and {@code timestamp}.
     */
    static long toCleanInsightsTimestamp(long relativeTo, long timestamp) {
        long diff = timestamp - relativeTo;
        long weekNumber = timestamp / DateUtils.WEEK_IN_MILLIS;
        return ((weekNumber * DateUtils.WEEK_IN_MILLIS) + diff) / 1000L;
    }

    static boolean isTimestampInReportingWeek(long timestamp) {
        return isTimestampInReportingWeek(getReportingWeekStart(), timestamp);
    }

    static boolean isTimestampInReportingWeek(long weekStart, long timestamp) {
        long weekEnd = weekStart + DateUtils.WEEK_IN_MILLIS;
        Log.i(TAG, weekStart + "-" + weekEnd);
        return timestamp > weekStart && timestamp < weekEnd;
    }

    static long getVersionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT < 28) {
            return packageInfo.versionCode;
        } else {
            return packageInfo.getLongVersionCode();
        }
    }

    /**
     * Gets the most recent week that is over based on the current time.
     *
     * @return start timestamp or 0 on parsing error
     */
    static long getReportingWeekStart() {
        return getReportingWeekStart(System.currentTimeMillis());
    }

    /**
     * Gets the most recent week that is over based on {@code timestamp}. This
     * is the testable version of {@link #getReportingWeekStart()}
     *
     * @return start timestamp or 0 on parsing error
     */
    static long getReportingWeekStart(long timestamp) {
        try {
            Date start = new Date(timestamp - DateUtils.WEEK_IN_MILLIS);
            return weekFormatter.parse(weekFormatter.format(start)).getTime();
        } catch (ParseException e) {
            // ignored
        }
        return 0;
    }

    /**
     * Reads the {@link InstallHistoryService} CSV log, debounces the duplicate events,
     * then converts it to {@link MatomoEvent} instances to be gathers.
     */
    static Collection<? extends MatomoEvent> parseInstallHistoryCsv(Context context, long weekStart) {
        try {
            File csv = InstallHistoryService.getInstallHistoryFile(context);
            List<String> lines = FileUtils.readLines(csv, Charset.defaultCharset());
            List<RawEvent> events = new ArrayList<>(lines.size());
            for (String line : lines) {
                RawEvent event = new RawEvent(line.split(","));
                if (isTimestampInReportingWeek(weekStart, event.timestamp)) {
                    events.add(event);
                }
            }
            Collections.sort(events, new Comparator<RawEvent>() {
                @Override
                public int compare(RawEvent e0, RawEvent e1) {
                    int applicationIdComparison = e0.applicationId.compareTo(e1.applicationId);
                    if (applicationIdComparison != 0) {
                        return applicationIdComparison;
                    }
                    int versionCodeComparison = Long.compare(e0.versionCode, e1.versionCode);
                    if (versionCodeComparison != 0) {
                        return versionCodeComparison;
                    }
                    int timestampComparison = Long.compare(e0.timestamp, e1.timestamp);
                    if (timestampComparison != 0) {
                        return timestampComparison;
                    }
                    return 0;
                }
            });
            List<MatomoEvent> toReport = new ArrayList<>();
            RawEvent previousEvent = new RawEvent(new String[]{"0", "", "0", ""});
            for (RawEvent event : events) {
                if (!previousEvent.equals(event)) {
                    toReport.add(new MatomoEvent(event));
                    previousEvent = event;
                }
            }
            // TODO add time to INSTALL_COMPLETE evnts, eg INSTALL_COMPLETE - INSTALL_STARTED
            return toReport;
        } catch (IOException e) {
            // ignored
        }
        return Collections.emptyList();
    }

    static String generateReport(Context context) {
        long weekStart = getReportingWeekStart();
        CleanInsightsReport cleanInsightsReport = new CleanInsightsReport();
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packageInfoList = pm.getInstalledPackages(0);
        Collections.sort(packageInfoList, new Comparator<PackageInfo>() {
            @Override
            public int compare(PackageInfo p1, PackageInfo p2) {
                return p1.packageName.compareTo(p2.packageName);
            }
        });
        App[] installedApps = InstalledAppProvider.Helper.all(context);
        final ArrayList<MatomoEvent> events = new ArrayList<>();
        for (PackageInfo packageInfo : packageInfoList) {
            boolean found = false;
            for (App app : installedApps) {
                if (packageInfo.packageName.equals(app.packageName)) {
                    found = true;
                    break;
                }
            }
            if (!found) continue;

            Log.i(TAG, "packageInfo.packageName " + packageInfo.packageName
                    + ":" + packageInfo.versionCode
                    + " " + packageInfo.lastUpdateTime
            );
            if (isTimestampInReportingWeek(weekStart, packageInfo.firstInstallTime)) {
                events.add(getFirstInstallEvent(packageInfo));
            }
            if (isTimestampInReportingWeek(weekStart, packageInfo.lastUpdateTime)) {
                events.add(getInstallerEvent(pm, packageInfo));
            }
            try {
                App app = App.getInstance(context, pm,
                        InstalledAppProvider.Helper.findByPackageName(context, packageInfo.packageName),
                        packageInfo.packageName);
                Log.i(TAG, "atime");
                // TODO app.installedApk.installedFile.length();
            } catch (CertificateEncodingException | IOException | PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            //Log.i(TAG, "APK: " + app.installedApk);
            //break; // TODO REMOVE ME
        }
        events.addAll(parseInstallHistoryCsv(context, weekStart));
        cleanInsightsReport.events = events.toArray(new MatomoEvent[0]);
        Log.i(TAG, "cleanInsightsReport.events: " + cleanInsightsReport.events.length);

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        String json = null;
        try {
            json = mapper.writeValueAsString(cleanInsightsReport);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        Log.i(TAG, "JSON:\n" + json);

        return json;
    }

    @Override
    public void onStopped() {
        super.onStopped();
        Log.i(TAG, "UpdateWorker");
        // TODO report bare minimum "timed out" message with period_start and period_end
    }

    /**
     * Bare minimum report data in CleanInsights/Matomo format.
     *
     * @see MatomoEvent
     * @see <a href="https://gitlab.com/cleaninsights/clean-insights-matomo-proxy#api">CleanInsights CIMP API</a>
     * @see <a href="https://matomo.org/docs/event-tracking/">Matomo Event Tracking</a>
     */
    private static class CleanInsightsReport {
        @JsonProperty
        MatomoEvent[] events = new MatomoEvent[0];
        @JsonProperty
        final long idsite = 5;
        @JsonProperty
        final String lang = Locale.getDefault().getLanguage();
        @JsonProperty
        final String ua = Utils.getUserAgent();
    }

    // TODO index through installed apks and report on the ones that have run in the reporting period
    private static MatomoEvent getApkOpenedEvent(long timestamp, PackageInfo packageInfo) {
        return getApkEvent(timestamp, packageInfo, "opened");
    }

    private static MatomoEvent getFirstInstallEvent(PackageInfo packageInfo) {
        return getApkEvent(packageInfo.firstInstallTime, packageInfo, "PackageInfo.firstInstall");
    }

    private static MatomoEvent getApkEvent(long timestamp, PackageInfo packageInfo, String action) {
        MatomoEvent matomoEvent = new MatomoEvent(timestamp);
        matomoEvent.category = "APK";
        matomoEvent.action = action;
        matomoEvent.name = packageInfo.packageName;
        matomoEvent.value = String.valueOf(getVersionCode(packageInfo));
        return matomoEvent;
    }

    /**
     * Which app store installed APKs.
     */
    private static MatomoEvent getInstallerEvent(PackageManager pm, PackageInfo packageInfo) {
        MatomoEvent matomoEvent = new MatomoEvent(packageInfo.lastUpdateTime);
        matomoEvent.category = "getInstallerPackageName";
        matomoEvent.action = pm.getInstallerPackageName(packageInfo.packageName);
        matomoEvent.name = packageInfo.packageName;
        matomoEvent.value = String.valueOf(getVersionCode(packageInfo));
        return matomoEvent;
    }

    /**
     * An event having to do with any package, e.g. installs/uninstalls/etc of
     * APKs, OTAs, map files, etc.
     */
    private static MatomoEvent getPackageEvent(long timestamp, String packageName, int versionCode, String action) {
        MatomoEvent matomoEvent = new MatomoEvent(timestamp);
        matomoEvent.category = "package";
        matomoEvent.action = action;
        matomoEvent.name = packageName;
        matomoEvent.value = String.valueOf(versionCode);
        return matomoEvent;
    }

    /**
     * An event to send to CleanInsights/Matomo with a period of a full,
     * normalized week.
     *
     * @see <a href="https://gitlab.com/cleaninsights/clean-insights-design/-/blob/master/schemas/cimp.schema.json">CleanInsights JSON Schema</a>
     * @see <a href="https://matomo.org/docs/event-tracking/">Matomo Event Tracking</a>
     */
    static class MatomoEvent {
        @JsonProperty
        String category;
        @JsonProperty
        String action;
        @JsonProperty
        String name;
        @JsonProperty
        final long period_start;
        @JsonProperty
        final long period_end;
        @JsonProperty
        final long times = 1;
        @JsonProperty
        String value;

        public MatomoEvent(long timestamp) {
            period_end = toCleanInsightsTimestamp(timestamp);
            period_start = period_end - (DateUtils.WEEK_IN_MILLIS / 1000);
        }

        public MatomoEvent(RawEvent rawEvent) {
            this(rawEvent.timestamp);
            category = "package";
            action = rawEvent.action;
            name = rawEvent.applicationId;
            value = String.valueOf(rawEvent.versionCode);
        }
    }

    /**
     * A raw event as read from {@link InstallHistoryService}'s CSV log file.
     * This should never leave the device as is, it must have data stripped
     * from it first.
     */
    static class RawEvent {
        final long timestamp;
        final String applicationId;
        final long versionCode;
        final String action;

        RawEvent(String[] o) {
            timestamp = Long.parseLong(o[0]);
            applicationId = o[1];
            versionCode = Long.parseLong(o[2]);
            action = o[3];
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RawEvent event = (RawEvent) o;
            return versionCode == event.versionCode &&
                    applicationId.equals(event.applicationId) &&
                    action.equals(event.action);
        }

        @Override
        public int hashCode() {
            return Objects.hash(applicationId, versionCode, action);
        }

        @Override
        public String toString() {
            return "RawEvent{" +
                    "timestamp=" + timestamp +
                    ", applicationId='" + applicationId + '\'' +
                    ", versionCode=" + versionCode +
                    ", action='" + action + '\'' +
                    '}';
        }
    }
}
