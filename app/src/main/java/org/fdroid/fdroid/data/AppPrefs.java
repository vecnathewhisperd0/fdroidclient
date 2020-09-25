package org.fdroid.fdroid.data;

import android.annotation.SuppressLint;

public class AppPrefs extends ValueObject {

    /**
     * True if all updates for this app are to be ignored.
     */
    public boolean ignoreAllUpdates;

    /**
     * The version code of the app for which the update should be ignored.
     */
    public int ignoreThisUpdate;

    /**
     * Don't notify of vulnerabilities in this app.
     */
    public boolean ignoreVulnerabilities;

    public AppPrefs(int ignoreThis, boolean ignoreAll, boolean ignoreVulns) {
        ignoreThisUpdate = ignoreThis;
        ignoreAllUpdates = ignoreAll;
        ignoreVulnerabilities = ignoreVulns;
    }

    public static AppPrefs createDefault() {
        return new AppPrefs(0, false, false);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof AppPrefs &&
                ((AppPrefs) o).ignoreAllUpdates == ignoreAllUpdates &&
                ((AppPrefs) o).ignoreThisUpdate == ignoreThisUpdate &&
                ((AppPrefs) o).ignoreVulnerabilities == ignoreVulnerabilities;
    }

    @Override
    @SuppressLint("NewApi")
    public int hashCode() {
        // The method implementation is automatically added to the APK even though lint says
        // it's not supported.
        int result = Boolean.hashCode(ignoreAllUpdates);
        result = 31 * result + ignoreThisUpdate;
        result = 31 * result + Boolean.hashCode(ignoreVulnerabilities);
        return result;
    }

    public AppPrefs createClone() {
        return new AppPrefs(ignoreThisUpdate, ignoreAllUpdates, ignoreVulnerabilities);
    }
}
