package org.fdroid.fdroid.net;

import androidx.annotation.NonNull;
import androidx.core.os.LocaleListCompat;

import org.fdroid.download.MirrorParameterManager;
import org.fdroid.download.MirrorData;
import org.fdroid.fdroid.Preferences;
import org.fdroid.fdroid.data.App;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class FDroidMirrorParameterManager implements MirrorParameterManager {

    @Override
    public void incrementMirrorSuccessCount(@NonNull String mirrorUrl) {
        Preferences prefs = Preferences.get();
        MirrorData data = prefs.getMirrorData(mirrorUrl);
        data.successes = data.successes + 1;
        prefs.setMirrorData(mirrorUrl, data);
    }

    @Override
    public void setMirrorSuccessCount(@NonNull String mirrorUrl, int successCount) {
        Preferences prefs = Preferences.get();
        MirrorData data = prefs.getMirrorData(mirrorUrl);
        data.successes = successCount;
        prefs.setMirrorData(mirrorUrl, data);
    }

    @Override
    public int getMirrorSuccessCount(@NonNull String mirrorUrl) {
        Preferences prefs = Preferences.get();
        return prefs.getMirrorData(mirrorUrl).successes;
    }

    @Override
    public void incrementMirrorErrorCount(@NonNull String mirrorUrl) {
        Preferences prefs = Preferences.get();
        MirrorData data = prefs.getMirrorData(mirrorUrl);
        data.errors = data.errors + 1;
        prefs.setMirrorData(mirrorUrl, data);
    }

    @Override
    public void setMirrorErrorCount(@NonNull String mirrorUrl, int errorCount) {
        Preferences prefs = Preferences.get();
        MirrorData data = prefs.getMirrorData(mirrorUrl);
        data.errors = errorCount;
        prefs.setMirrorData(mirrorUrl, data);
    }

    @Override
    public int getMirrorErrorCount(@NonNull String mirrorUrl) {
        Preferences prefs = Preferences.get();
        return prefs.getMirrorData(mirrorUrl).errors;
    }

    @Override
    public boolean useLocalMirrors() {
        Preferences prefs = Preferences.get();
        return prefs.isUseLocalSet();
    }

    @Override
    public boolean useRemoteMirrors() {
        Preferences prefs = Preferences.get();
        return prefs.isUseRemoteSet();
    }

    @NonNull
    @Override
    public List<String> getCurrentLocations() {
        ArrayList<String> stringList = new ArrayList<String>();
        LocaleListCompat localeList = App.getLocales();
        for (int i = 0; i < localeList.size(); i++) {
            stringList.add(localeList.get(i).getCountry());
        }
        return stringList;
    }
}
