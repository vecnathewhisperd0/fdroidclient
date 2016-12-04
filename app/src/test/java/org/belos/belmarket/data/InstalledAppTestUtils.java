package org.belos.belmarket.data;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

public class InstalledAppTestUtils {

    /**
     * Will tell {@code pm} that we are installing {@code packageName}, and then update the
     * "installed apps" table in the database.
     */
    public static void install(Context context,
                               String packageName,
                               int versionCode, String versionName) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.versionCode = versionCode;
        info.versionName = versionName;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.publicSourceDir = "/tmp/mock-location";
        String hashType = "sha256";
        String hash = "00112233445566778899aabbccddeeff";
        InstalledAppProviderService.insertAppIntoDb(context, info, hashType, hash);
    }

}
