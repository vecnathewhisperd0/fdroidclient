package org.fdroid.fdroid.nearby;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.fdroid.fdroid.FDroidApp;
import org.fdroid.fdroid.Preferences;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.data.Apk;
import org.fdroid.fdroid.data.App;
import org.fdroid.fdroid.data.InstalledApp;
import org.fdroid.fdroid.data.InstalledAppProvider;
import org.fdroid.fdroid.data.SanitizedFile;
import org.fdroid.fdroid.data.Schema.ApkTable;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.security.cert.CertificateEncodingException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The {@link SwapService} deals with managing the entire workflow from selecting apps to
 * swap, to invoking this class to prepare the webroot, to enabling various communication protocols.
 * This class deals specifically with the webroot side of things, ensuring we have a valid index.jar
 * and the relevant .apk and icon files available.
 */
public final class LocalRepoManager {
    private static final String TAG = "LocalRepoManager";

    private final Context context;
    private final PackageManager pm;
    private final AssetManager assetManager;
    private final String fdroidPackageName;

    public static final String[] WEB_ROOT_ASSET_FILES = {
            "swap-icon.png",
            "swap-tick-done.png",
            "swap-tick-not-done.png",
    };

    private final Map<String, App> apps = new ConcurrentHashMap<>();

    private final SanitizedFile entryJar;
    private final SanitizedFile entryJarUnsigned;
    private final SanitizedFile indexV2Json;
    private final SanitizedFile webRoot;
    private final SanitizedFile fdroidDir;
    private final SanitizedFile fdroidDirCaps;
    private final SanitizedFile repoDir;
    private final SanitizedFile repoDirCaps;
    private final SanitizedFile iconsDir;

    @Nullable
    private static LocalRepoManager localRepoManager;

    @NonNull
    public static LocalRepoManager get(Context context) {
        if (localRepoManager == null) {
            localRepoManager = new LocalRepoManager(context);
        }
        return localRepoManager;
    }

    private LocalRepoManager(Context c) {
        context = c.getApplicationContext();
        pm = c.getPackageManager();
        assetManager = c.getAssets();
        fdroidPackageName = c.getPackageName();

        webRoot = SanitizedFile.knownSanitized(c.getFilesDir());
        /* /fdroid/repo is the standard path for user repos */
        fdroidDir = new SanitizedFile(webRoot, "fdroid");
        fdroidDirCaps = new SanitizedFile(webRoot, "FDROID");
        repoDir = new SanitizedFile(fdroidDir, "repo");
        repoDirCaps = new SanitizedFile(fdroidDirCaps, "REPO");
        iconsDir = new SanitizedFile(repoDir, "icons");
        //entryJar = new SanitizedFile(repoDir, "entry.jar"); // TODO IndexV2Updater.SIGNED_ENTRY_FILE_NAME
        entryJar = SanitizedFile.knownSanitized(new File("/tmp/entry.jar"));
        entryJarUnsigned = new SanitizedFile(repoDir, "unsigned.jar");
        //indexV2Json = new SanitizedFile(repoDir, "index-v2.json");  // TODO IndexV2Updater.INDEX_FILE_NAME
        indexV2Json = SanitizedFile.knownSanitized(new File("/tmp/index-v2.json"));

        if (!fdroidDir.exists() && !fdroidDir.mkdir()) {
            Log.e(TAG, "Unable to create empty base: " + fdroidDir);
        }

        if (!repoDir.exists() && !repoDir.mkdir()) {
            Log.e(TAG, "Unable to create empty repo: " + repoDir);
        }

        if (!iconsDir.exists() && !iconsDir.mkdir()) {
            Log.e(TAG, "Unable to create icons folder: " + iconsDir);
        }
    }

    private String writeFdroidApkToWebroot() {
        ApplicationInfo appInfo;
        String fdroidClientURL = "https://f-droid.org/F-Droid.apk";

        try {
            appInfo = pm.getApplicationInfo(fdroidPackageName, PackageManager.GET_META_DATA);
            SanitizedFile apkFile = SanitizedFile.knownSanitized(appInfo.publicSourceDir);
            SanitizedFile fdroidApkLink = new SanitizedFile(fdroidDir, "F-Droid.apk");
            attemptToDelete(fdroidApkLink);
            if (Utils.symlinkOrCopyFileQuietly(apkFile, fdroidApkLink)) {
                fdroidClientURL = "/" + fdroidDir.getName() + "/" + fdroidApkLink.getName();
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not set up F-Droid apk in the webroot", e);
        }
        return fdroidClientURL;
    }

    public void writeIndexPage(String repoAddress) {
        final String fdroidClientURL = writeFdroidApkToWebroot();
        try {
            File indexHtml = new File(webRoot, "index.html");
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(assetManager.open("index.template.html"), "UTF-8"));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(indexHtml)));

            StringBuilder builder = new StringBuilder();
            for (App app : apps.values()) {
                builder.append("<li><a href=\"/fdroid/repo/")
                        .append(app.installedApk.apkName)
                        .append("\"><img width=\"32\" height=\"32\" src=\"/fdroid/repo/icons/")
                        .append(app.packageName)
                        .append("_")
                        .append(app.installedApk.versionCode)
                        .append(".png\">")
                        .append(app.name)
                        .append("</a></li>\n");
            }

            String line;
            while ((line = in.readLine()) != null) {
                line = line.replaceAll("\\{\\{REPO_URL\\}\\}", repoAddress);
                line = line.replaceAll("\\{\\{CLIENT_URL\\}\\}", fdroidClientURL);
                line = line.replaceAll("\\{\\{APP_LIST\\}\\}", builder.toString());
                out.write(line);
            }
            in.close();
            out.close();

            for (final String file : WEB_ROOT_ASSET_FILES) {
                InputStream assetIn = assetManager.open(file);
                OutputStream assetOut = new FileOutputStream(new File(webRoot, file));
                Utils.copy(assetIn, assetOut);
                assetIn.close();
                assetOut.close();
            }

            // make symlinks/copies in each subdir of the repo to make sure that
            // the user will always find the bootstrap page.
            symlinkEntireWebRootElsewhere("../", fdroidDir);
            symlinkEntireWebRootElsewhere("../../", repoDir);

            // add in /FDROID/REPO to support bad QR Scanner apps
            attemptToMkdir(fdroidDirCaps);
            attemptToMkdir(repoDirCaps);

            symlinkEntireWebRootElsewhere("../", fdroidDirCaps);
            symlinkEntireWebRootElsewhere("../../", repoDirCaps);

        } catch (IOException e) {
            Log.e(TAG, "Error writing local repo index", e);
        }
    }

    private static void attemptToMkdir(@NonNull File dir) throws IOException {
        if (dir.exists()) {
            if (dir.isDirectory()) {
                return;
            }
            throw new IOException("Can't make directory " + dir + " - it is already a file.");
        }

        if (!dir.mkdir()) {
            throw new IOException("An error occurred trying to create directory " + dir);
        }
    }

    private static void attemptToDelete(@NonNull File file) {
        if (!file.delete()) {
            Log.e(TAG, "Could not delete \"" + file.getAbsolutePath() + "\".");
        }
    }

    private void symlinkEntireWebRootElsewhere(String symlinkPrefix, File directory) {
        symlinkFileElsewhere("index.html", symlinkPrefix, directory);
        for (final String fileName : WEB_ROOT_ASSET_FILES) {
            symlinkFileElsewhere(fileName, symlinkPrefix, directory);
        }
    }

    private void symlinkFileElsewhere(String fileName, String symlinkPrefix, File directory) {
        SanitizedFile index = new SanitizedFile(directory, fileName);
        attemptToDelete(index);
        Utils.symlinkOrCopyFileQuietly(new SanitizedFile(new File(directory, symlinkPrefix), fileName), index);
    }

    private void deleteContents(File path) {
        if (path.exists()) {
            for (File file : path.listFiles()) {
                if (file.isDirectory()) {
                    deleteContents(file);
                } else {
                    attemptToDelete(file);
                }
            }
        }
    }

    /**
     * Get the {@code index.jar} file that represents the local swap repo.
     */
    public File getIndexJar() {
        return entryJar;
    }

    public File getWebRoot() {
        return webRoot;
    }

    public void deleteRepo() {
        deleteContents(repoDir);
    }

    public void copyApksToRepo() {
        copyApksToRepo(new ArrayList<>(apps.keySet()));
    }

    private void copyApksToRepo(List<String> appsToCopy) {
        for (final String packageName : appsToCopy) {
            final App app = apps.get(packageName);

            if (app.installedApk != null) {
                SanitizedFile outFile = new SanitizedFile(repoDir, app.installedApk.apkName);
                if (Utils.symlinkOrCopyFileQuietly(app.installedApk.installedFile, outFile)) {
                    continue;
                }
            }
            // if we got here, something went wrong
            throw new IllegalStateException("Unable to copy APK");
        }
    }

    public void addApp(Context context, String packageName) {
        App app = null;
        try {
            InstalledApp installedApp = InstalledAppProvider.Helper.findByPackageName(context, packageName);
            app = App.getInstance(context, pm, installedApp, packageName);
            if (app == null || !app.isValid()) {
                return;
            }
        } catch (PackageManager.NameNotFoundException | CertificateEncodingException | IOException e) {
            Log.e(TAG, "Error adding app to local repo", e);
            return;
        }
        Utils.debugLog(TAG, "apps.put: " + packageName);
        apps.put(packageName, app);
    }

    public void copyIconsToRepo() {
        ApplicationInfo appInfo;
        for (final App app : apps.values()) {
            if (app.installedApk != null) {
                try {
                    appInfo = pm.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA);
                    copyIconToRepo(appInfo.loadIcon(pm), app.packageName, app.installedApk.versionCode);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "Error getting app icon", e);
                }
            }
        }
    }

    /**
     * Extracts the icon from an APK and writes it to the repo as a PNG
     */
    private void copyIconToRepo(Drawable drawable, String packageName, int versionCode) {
        Bitmap bitmap;
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(), Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
        }
        File png = getIconFile(packageName, versionCode);
        OutputStream out;
        try {
            out = new BufferedOutputStream(new FileOutputStream(png));
            bitmap.compress(CompressFormat.PNG, 100, out);
            out.close();
        } catch (Exception e) {
            Log.e(TAG, "Error copying icon to repo", e);
        }
    }

    private File getIconFile(String packageName, int versionCode) {
        return new File(iconsDir, App.getIconName(packageName, versionCode));
    }

    /**
     * Helper class to aid in constructing index.xml file.
     */
    public static final class IndexV2Builder {

        @NonNull
        private static final DateFormat dateToStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        public static void build(Context context, Map<String, App> apps, OutputStream output)
                throws IOException, LocalRepoKeyStore.InitException {

            ObjectMapper mapper = new ObjectMapper();
            mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

            Map<String, Object> repoMap = new HashMap<>();
            repoMap.put("icon", "blah.png");
            repoMap.put("name", Preferences.get().getLocalRepoName() + " on " + FDroidApp.ipAddressString);
            repoMap.put("timestamp", System.currentTimeMillis());
            repoMap.put("version", "10"); // TODO
            repoMap.put("description", "A local FDroid repo generated from apps installed on "
                    + Preferences.get().getLocalRepoName());

            Map<String, Object[]> packagesMap = new HashMap<>();
            for (App app : apps.values()) {
                app.suggestedVersionCode = app.installedApk.versionCode;

                HashSet<String> categories = new HashSet<>();
                if (app.categories != null && app.categories.length > 0) {
                    categories.addAll(Arrays.asList(app.categories));
                }
                categories.add(Preferences.get().getLocalRepoName());
                app.categories = categories.toArray(new String[0]);
                // TODO? tag("icon", app.iconFromApk);
                packagesMap.put(app.packageName, getPackageList(app));
            }

            Map<String, Object> indexMap = new HashMap<>();
            indexMap.put("repo", repoMap);
            indexMap.put("apps", apps);
            indexMap.put("packages", packagesMap);

            mapper.writeValue(output, indexMap);
            output.close();
        }

        /**
         * Alias for {@link IndexV2Builder#tag(String, String)}
         * that accepts a date instead of a string.
         *
         * @see IndexV2Builder#tag(String, String)
         */
        private static void tag(String name, Date date) throws IOException {
            //tag(name, dateToStr.format(date));
        }

        private static HashMap<String, Object>[] getPackageList(App app) throws IOException {
            HashMap<String, Object>[] packageMap = new HashMap[1];
            packageMap[0] = new HashMap<>();
            packageMap[0].put("versionName", app.installedApk.versionName);
            packageMap[0].put("versionCode", app.installedApk.versionCode);
            packageMap[0].put(ApkTable.Cols.NAME, app.installedApk.apkName);
            // TODO packageMap[0].putHash(app);
            packageMap[0].put(ApkTable.Cols.SIGNER, app.installedApk.signer.toLowerCase(Locale.US));
            // TODO packageMap[0].put("sig", app.installedApk.signer.toLowerCase(Locale.US)); // required to support old fdroidclient versions
            packageMap[0].put(ApkTable.Cols.SIZE, app.installedApk.installedFile.length());
            packageMap[0].put(ApkTable.Cols.ADDED_DATE, app.installedApk.added);
            if (app.installedApk.minSdkVersion > Apk.SDK_VERSION_MIN_VALUE) {
                packageMap[0].put(ApkTable.Cols.MIN_SDK_VERSION, app.installedApk.minSdkVersion);
            }
            if (app.installedApk.targetSdkVersion > app.installedApk.minSdkVersion) {
                packageMap[0].put(ApkTable.Cols.TARGET_SDK_VERSION, app.installedApk.targetSdkVersion);
            }
            if (app.installedApk.maxSdkVersion < Apk.SDK_VERSION_MAX_VALUE) {
                packageMap[0].put(ApkTable.Cols.MAX_SDK_VERSION, app.installedApk.maxSdkVersion);
            }
            return packageMap;
        }
    }

    public void writeEntryJar() throws IOException, LocalRepoKeyStore.InitException {
        BufferedOutputStream bo = new BufferedOutputStream(new FileOutputStream(indexV2Json));
        IndexV2Builder.build(context, apps, bo);
        //Utils.hashBytes("SHA-256");

        ObjectMapper mapper = new ObjectMapper();
        bo = new BufferedOutputStream(new FileOutputStream(entryJarUnsigned));
        JarOutputStream jo = new JarOutputStream(bo);
        JarEntry je = new JarEntry("entry.json"); // TODO IndexV2Updater.DATA_FILE_NAME
        jo.putNextEntry(je);
        Map<String, Object> indexMap = new HashMap<>();
        indexMap.put("name", "index-v2.json");
        indexMap.put("sha256", "c4bd600d7ed554a69201d7aaa4f7f7ef7cd13dc20cdd4af254d17ffd12bd7cdc"); // TODO
        indexMap.put("size", indexV2Json.length());
        Map<String, Object> filesMap = new HashMap<>();
        filesMap.put("index", indexMap);
        Map<String, Object> entryMap = new HashMap<>();
        entryMap.put("version", "30001"); // TODO
        entryMap.put("timestamp", System.currentTimeMillis());
        entryMap.put("maxAge", "14"); // TODO
        filesMap.put("files", filesMap);
        mapper.writeValue(jo, entryMap);
        jo.close();
        bo.close();

        try {
            LocalRepoKeyStore.get(context).signZip(entryJarUnsigned, entryJar);
        } catch (LocalRepoKeyStore.InitException e) {
            throw new IOException("Could not sign index - keystore failed to initialize");
        } finally {
            attemptToDelete(entryJarUnsigned);
        }

    }

}
