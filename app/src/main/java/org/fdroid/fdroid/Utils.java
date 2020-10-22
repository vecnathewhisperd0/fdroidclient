/*
 * Copyright (C) 2010-12  Ciaran Gultnieks, ciaran@ciarang.com
 * Copyright (C) 2019 Michael Pöhn, michael.poehn@fsfe.org
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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.Editable;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.TypefaceSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.encode.Contents;
import com.google.zxing.encode.QRCodeEncoder;
import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;
import com.nostra13.universalimageloader.core.display.FadeInBitmapDisplayer;
import com.nostra13.universalimageloader.utils.StorageUtils;

import org.fdroid.fdroid.compat.FileCompat;
import org.fdroid.fdroid.data.App;
import org.fdroid.fdroid.data.Repo;
import org.fdroid.fdroid.data.SanitizedFile;
import org.xml.sax.XMLReader;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class Utils {
    private static final String TAG = "Utils";

    private static final int BUFFER_SIZE = 4096;

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss");

    private static final String[] FRIENDLY_SIZE_FORMAT = {
            "%.0f B", "%.0f KiB", "%.1f MiB", "%.2f GiB",
    };

    private static DisplayImageOptions.Builder defaultDisplayImageOptionsBuilder;
    private static DisplayImageOptions repoAppDisplayImageOptions;

    private static Pattern safePackageNamePattern;

    private static Handler toastHandler;

    public static final String FALLBACK_ICONS_DIR = "icons";

    /*
     * @param dpiMultiplier Lets you grab icons for densities larger or
     * smaller than that of your device by some fraction. Useful, for example,
     * if you want to display a 48dp image at twice the size, 96dp, in which
     * case you'd use a dpiMultiplier of 2.0 to get an image twice as big.
     */
    public static String getIconsDir(final Context context, final double dpiMultiplier) {
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final double dpi = metrics.densityDpi * dpiMultiplier;
        if (dpi >= 640) {
            return "icons-640";
        }
        if (dpi >= 480) {
            return "icons-480";
        }
        if (dpi >= 320) {
            return "icons-320";
        }
        if (dpi >= 240) {
            return "icons-240";
        }
        if (dpi >= 160) {
            return "icons-160";
        }

        return "icons-120";
    }

    /**
     * @return the directory where cached icons/feature graphics/screenshots are stored
     */
    public static File getImageCacheDir(Context context) {
        File cacheDir = StorageUtils.getCacheDirectory(context.getApplicationContext(), true);
        return new File(cacheDir, "icons");
    }

    public static long getImageCacheDirAvailableMemory(Context context) {
        File statDir = getImageCacheDir(context);
        while (statDir != null && !statDir.exists()) {
            statDir = statDir.getParentFile();
        }
        if (statDir == null) {
            return 50 * 1024 * 1024; // just return a minimal amount
        }
        StatFs stat = new StatFs(statDir.getPath());
        if (Build.VERSION.SDK_INT < 18) {
            return (long) stat.getAvailableBlocks() * (long) stat.getBlockSize();
        } else {
            return stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        }
    }

    public static long getImageCacheDirTotalMemory(Context context) {
        File statDir = getImageCacheDir(context);
        while (statDir != null && !statDir.exists()) {
            statDir = statDir.getParentFile();
        }
        if (statDir == null) {
            return 100 * 1024 * 1024; // just return a minimal amount
        }
        StatFs stat = new StatFs(statDir.getPath());
        if (Build.VERSION.SDK_INT < 18) {
            return (long) stat.getBlockCount() * (long) stat.getBlockSize();
        } else {
            return stat.getBlockCountLong() * stat.getBlockSizeLong();
        }
    }

    public static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (true) {
            int count = input.read(buffer);
            if (count == -1) {
                break;
            }
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    /**
     * Attempt to symlink, but if that fails, it will make a copy of the file.
     */
    public static boolean symlinkOrCopyFileQuietly(SanitizedFile inFile, SanitizedFile outFile) {
        return FileCompat.symlink(inFile, outFile) || copyQuietly(inFile, outFile);
    }

    /**
     * Read the input stream until it reaches the end, ignoring any exceptions.
     */
    public static void consumeStream(InputStream stream) {
        final byte[] buffer = new byte[256];
        try {
            int read;
            do {
                read = stream.read(buffer);
            } while (read != -1);
        } catch (IOException e) {
            // Ignore...
        }
    }

    public static boolean copyQuietly(File inFile, File outFile) {
        InputStream input = null;
        OutputStream output = null;
        try {
            input = new FileInputStream(inFile);
            output = new FileOutputStream(outFile);
            Utils.copy(input, output);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "I/O error when copying a file", e);
            return false;
        } finally {
            closeQuietly(output);
            closeQuietly(input);
        }
    }

    public static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ioe) {
            // ignore
        }
    }

    public static String getFriendlySize(long size) {
        double s = size;
        int i = 0;
        while (i < FRIENDLY_SIZE_FORMAT.length - 1 && s >= 1024) {
            s = (100 * s / 1024) / 100.0;
            i++;
        }
        return String.format(FRIENDLY_SIZE_FORMAT[i], s);
    }

    private static final String[] ANDROID_VERSION_NAMES = {
            "?",     // 0, undefined
            "1.0",   // 1
            "1.1",   // 2
            "1.5",   // 3
            "1.6",   // 4
            "2.0",   // 5
            "2.0.1", // 6
            "2.1",   // 7
            "2.2",   // 8
            "2.3",   // 9
            "2.3.3", // 10
            "3.0",   // 11
            "3.1",   // 12
            "3.2",   // 13
            "4.0",   // 14
            "4.0.3", // 15
            "4.1",   // 16
            "4.2",   // 17
            "4.3",   // 18
            "4.4",   // 19
            "4.4W",  // 20
            "5.0",   // 21
            "5.1",   // 22
            "6.0",   // 23
            "7.0",   // 24
            "7.1",   // 25
            "8.0",   // 26
            "8.1",   // 27
            "9.0",   // 28
            "10.0",  // 29
            "11.0",  // 30
    };

    public static String getAndroidVersionName(int sdkLevel) {
        if (sdkLevel < 0) {
            return ANDROID_VERSION_NAMES[0];
        }
        if (sdkLevel >= ANDROID_VERSION_NAMES.length) {
            return String.format(Locale.ENGLISH, "v%d", sdkLevel);
        }
        return ANDROID_VERSION_NAMES[sdkLevel];
    }

    // return a fingerprint formatted for display
    public static String formatFingerprint(Context context, String fingerprint) {
        if (TextUtils.isEmpty(fingerprint)
                || fingerprint.length() != 64 // SHA-256 is 64 hex chars
                || fingerprint.matches(".*[^0-9a-fA-F].*")) { // its a hex string
            return context.getString(R.string.bad_fingerprint);
        }
        StringBuilder displayFP = new StringBuilder(fingerprint.substring(0, 2));
        for (int i = 2; i < fingerprint.length(); i = i + 2) {
            displayFP.append(" ").append(fingerprint.substring(i, i + 2));
        }
        return displayFP.toString();
    }

    @NonNull
    public static Uri getLocalRepoUri(Repo repo) {
        if (TextUtils.isEmpty(repo.address)) {
            return Uri.parse("http://wifi-not-enabled");
        }
        Uri uri = Uri.parse(repo.address);
        Uri.Builder b = uri.buildUpon();
        if (!TextUtils.isEmpty(repo.fingerprint)) {
            b.appendQueryParameter("fingerprint", repo.fingerprint);
        }
        String scheme = Preferences.get().isLocalRepoHttpsEnabled() ? "https" : "http";
        b.scheme(scheme);
        return b.build();
    }

    public static Uri getSharingUri(Repo repo) {
        if (TextUtils.isEmpty(repo.address)) {
            return Uri.parse("http://wifi-not-enabled");
        }
        Uri localRepoUri = getLocalRepoUri(repo);
        Uri.Builder b = localRepoUri.buildUpon();
        b.scheme(localRepoUri.getScheme().replaceFirst("http", "fdroidrepo"));
        b.appendQueryParameter("swap", "1");
        if (!TextUtils.isEmpty(FDroidApp.bssid)) {
            b.appendQueryParameter("bssid", FDroidApp.bssid);
            if (!TextUtils.isEmpty(FDroidApp.ssid)) {
                b.appendQueryParameter("ssid", FDroidApp.ssid);
            }
        }
        return b.build();
    }

    /**
     * Create a standard {@link PackageManager} {@link Uri} for pointing to an app.
     */
    public static Uri getPackageUri(String packageName) {
        return Uri.parse("package:" + packageName);
    }

    public static String calcFingerprint(String keyHexString) {
        if (TextUtils.isEmpty(keyHexString)
                || keyHexString.matches(".*[^a-fA-F0-9].*")) {
            Log.e(TAG, "Signing key certificate was blank or contained a non-hex-digit!");
            return null;
        }
        return calcFingerprint(Hasher.unhex(keyHexString));
    }

    public static String calcFingerprint(Certificate cert) {
        if (cert == null) {
            return null;
        }
        try {
            return calcFingerprint(cert.getEncoded());
        } catch (CertificateEncodingException e) {
            return null;
        }
    }

    private static String calcFingerprint(byte[] key) {
        if (key == null) {
            return null;
        }
        if (key.length < 256) {
            Log.e(TAG, "key was shorter than 256 bytes (" + key.length + "), cannot be valid!");
            return null;
        }
        String ret = null;
        try {
            // keytool -list -v gives you the SHA-256 fingerprint
            MessageDigest digest = MessageDigest.getInstance("sha256");
            digest.update(key);
            byte[] fingerprint = digest.digest();
            Formatter formatter = new Formatter(new StringBuilder());
            for (byte aFingerprint : fingerprint) {
                formatter.format("%02X", aFingerprint);
            }
            ret = formatter.toString();
            formatter.close();
        } catch (Throwable e) { // NOPMD
            Log.w(TAG, "Unable to get certificate fingerprint", e);
        }
        return ret;
    }


    /**
     * Get the fingerprint used to represent an APK signing key in F-Droid.
     * This is a custom fingerprint algorithm that was kind of accidentally
     * created, but is still in use.
     *
     * @see #getPackageSig(PackageInfo)
     * @see org.fdroid.fdroid.data.Apk#sig
     */
    public static String getsig(byte[] rawCertBytes) {
        return Utils.hashBytes(toHexString(rawCertBytes).getBytes(), "md5");
    }

    /**
     * Get the fingerprint used to represent an APK signing key in F-Droid.
     * This is a custom fingerprint algorithm that was kind of accidentally
     * created, but is still in use.
     *
     * @see #getsig(byte[])
     * @see org.fdroid.fdroid.data.Apk#sig
     */
    public static String getPackageSig(PackageInfo info) {
        if (info == null || info.signatures == null || info.signatures.length < 1) {
            return "";
        }
        Signature sig = info.signatures[0];
        String sigHash = "";
        try {
            Hasher hash = new Hasher("MD5", sig.toCharsString().getBytes());
            sigHash = hash.getHash();
        } catch (NoSuchAlgorithmException e) {
            // ignore
        }
        return sigHash;
    }

    /**
     * There is a method {@link java.util.Locale#forLanguageTag(String)} which would be useful
     * for this, however it doesn't deal with android-specific language tags, which are a little
     * different. For example, android language tags may have an "r" before the country code,
     * such as "zh-rHK", however {@link java.util.Locale} expects them to be "zr-HK".
     */
    public static Locale getLocaleFromAndroidLangTag(String languageTag) {
        if (TextUtils.isEmpty(languageTag)) {
            return null;
        }

        final String[] parts = languageTag.split("-");
        if (parts.length == 1) {
            return new Locale(parts[0]);
        }
        if (parts.length == 2) {
            String country = parts[1];
            // Some languages have an "r" before the country as per the values folders, such
            // as "zh-rCN". As far as the Locale class is concerned, the "r" is
            // not helpful, and this should be "zh-CN". Thus, we will
            // strip the "r" when found.
            if (country.charAt(0) == 'r' && country.length() == 3) {
                country = country.substring(1);
            }
            return new Locale(parts[0], country);
        }
        Log.e(TAG, "Locale could not be parsed from language tag: " + languageTag);
        return new Locale(languageTag);
    }

    /**
     * Since there have been vulnerabilities in EXIF processing in Android, this
     * disables all use of EXIF.
     *
     * @see <a href="https://securityaffairs.co/wordpress/51043/mobile-2/android-cve-2016-3862-flaw.html">CVE-2016-3862</a>
     */
    public static DisplayImageOptions.Builder getDefaultDisplayImageOptionsBuilder() {
        if (defaultDisplayImageOptionsBuilder == null) {
            defaultDisplayImageOptionsBuilder = createDefaultDisplayImageOptionsBuilder();
        }
        return defaultDisplayImageOptionsBuilder;
    }

    /**
     * Gets the {@link DisplayImageOptions} instance used to configure
     * {@link com.nostra13.universalimageloader.core.ImageLoader} instances
     * used to display app icons.  It lazy loads a reusable static instance.
     */
    public static DisplayImageOptions getRepoAppDisplayImageOptions() {
        if (repoAppDisplayImageOptions == null) {
            repoAppDisplayImageOptions = createDefaultDisplayImageOptionsBuilder()
                    .showImageOnLoading(R.drawable.ic_repo_app_default)
                    .showImageForEmptyUri(R.drawable.ic_repo_app_default)
                    .showImageOnFail(R.drawable.ic_repo_app_default)
                    .displayer(new FadeInBitmapDisplayer(200, true, true, false))
                    .build();
        }
        return repoAppDisplayImageOptions;
    }

    private static DisplayImageOptions.Builder createDefaultDisplayImageOptionsBuilder() {
        return new DisplayImageOptions.Builder()
                .cacheInMemory(true)
                .cacheOnDisk(true)
                .considerExifParams(false)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .imageScaleType(ImageScaleType.EXACTLY);
    }

    /**
     * If app has an iconUrl we feed that to UIL, otherwise we ask the PackageManager which will
     * return the app's icon directly when the app is installed.
     * We fall back to the placeholder icon otherwise.
     */
    public static void setIconFromRepoOrPM(@NonNull App app, ImageView iv, Context context) {
        if (app.getIconUrl(iv.getContext()) == null) {
            try {
                iv.setImageDrawable(context.getPackageManager().getApplicationIcon(app.packageName));
            } catch (PackageManager.NameNotFoundException e) {
                DisplayImageOptions options = Utils.getRepoAppDisplayImageOptions();
                iv.setImageDrawable(options.shouldShowImageForEmptyUri()
                        ? options.getImageForEmptyUri(FDroidApp.getInstance().getResources())
                        : null);
            }
        } else {
            ImageLoader.getInstance().displayImage(
                    app.getIconUrl(iv.getContext()), iv, Utils.getRepoAppDisplayImageOptions());
        }
    }

    // this is all new stuff being added
    public static String hashBytes(byte[] input, String algo) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] hashBytes = md.digest(input);
            String hash = toHexString(hashBytes);

            md.reset();
            return hash;
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Device does not support " + algo + " MessageDisgest algorithm");
            return null;
        }
    }

    /**
     * Get the checksum hash of the file {@code apk} using the algorithm in {@code algo}.
     * {@code apk} must exist on the filesystem and {@code algo} must be supported
     * by this device, otherwise an {@link IllegalArgumentException} is thrown.  This
     * method must be very defensive about checking whether the file exists, since APKs
     * can be uninstalled/deleted in background at any time, even if this is in the
     * middle of running.
     * <p>
     * This also will run into filesystem corruption if the device is having trouble.
     * So hide those so F-Droid does not pop up crash reports about that. As such this
     * exception-message-parsing-and-throwing-a-new-ignorable-exception-hackery is
     * probably warranted. See https://www.gitlab.com/fdroid/fdroidclient/issues/855
     * for more detail.
     */
    @Nullable
    public static String getBinaryHash(File apk, String algo) {
        FileInputStream fis = null;
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            fis = new FileInputStream(apk);
            BufferedInputStream bis = new BufferedInputStream(fis);

            byte[] dataBytes = new byte[8192];
            int nread;
            while ((nread = bis.read(dataBytes)) != -1) { // NOPMD Avoid assignments in operands
                md.update(dataBytes, 0, nread);
            }

            byte[] mdbytes = md.digest();
            return toHexString(mdbytes);
        } catch (IOException e) {
            String message = e.getMessage();
            if (message.contains("read failed: EIO (I/O error)")) {
                Utils.debugLog(TAG, "potential filesystem corruption while accessing " + apk + ": " + message);
            } else if (message.contains(" ENOENT ")) {
                Utils.debugLog(TAG, apk + " vanished: " + message);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
        } finally {
            closeQuietly(fis);
        }
        return null;
    }

    /**
     * Computes the base 16 representation of the byte array argument.
     *
     * @param bytes an array of bytes.
     * @return the bytes represented as a string of lowercase hexadecimal digits.
     * @see <a href="https://stackoverflow.com/a/9855338">source</a>
     */
    public static String toHexString(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_LOOKUP_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_LOOKUP_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    private static final char[] HEX_LOOKUP_ARRAY = "0123456789abcdef".toCharArray();

    public static int parseInt(String str, int fallback) {
        if (str == null || str.length() == 0) {
            return fallback;
        }
        int result;
        try {
            result = Integer.parseInt(str);
        } catch (NumberFormatException e) {
            result = fallback;
        }
        return result;
    }

    @Nullable
    public static String[] parseCommaSeparatedString(String values) {
        return values == null || values.length() == 0 ? null : values.split(",");
    }

    @Nullable
    public static String serializeCommaSeparatedString(@Nullable String[] values) {
        return values == null || values.length == 0 ? null : TextUtils.join(",", values);
    }

    /**
     * Parses a date string into a {@link LocalDate}
     */
    @Nullable
    public static LocalDate parseLocalDate(@Nullable String str, @Nullable LocalDate fallback) {
        if (TextUtils.isEmpty(str)) {
            return fallback;
        }
        LocalDate result;
        try {
            result = LocalDate.parse(str);
        } catch (DateTimeParseException e) {
            e.printStackTrace();
            result = fallback;
        }
        return result;
    }

    /**
     * Formats a {@link LocalDate} into a date string
     */
    @Nullable
    public static String formatLocalDate(@Nullable LocalDate localDate, @Nullable String fallback) {
        if (localDate == null) {
            return fallback;
        }
        return DateTimeFormatter.ISO_LOCAL_DATE.format(localDate);
    }

    /**
     * Parses a date/time string into a {@link LocalDateTime}
     */
    @Nullable
    public static LocalDateTime parseLocalDateTime(@Nullable String str, @Nullable LocalDateTime fallback) {
        if (TextUtils.isEmpty(str)) {
            return fallback;
        }
        LocalDateTime result;
        try {
            result = LocalDateTime.parse(str, DATE_TIME);
        } catch (DateTimeParseException e) {
            e.printStackTrace();
            result = fallback;
        }
        return result;
    }

    /**
     * Formats a {@link LocalDateTime} into a date/time string
     */
    @Nullable
    public static String formatLocalDateTime(@Nullable LocalDateTime localDateTime, @Nullable String fallback) {
        if (localDateTime == null) {
            return fallback;
        }
        return DATE_TIME.format(localDateTime);
    }

    /**
     * Formats the app name using "sans-serif" and then appends the summary after a space with
     * "sans-serif-light". Doesn't mandate any font sizes or any other styles, that is up to the
     * {@link android.widget.TextView} which it ends up being displayed in.
     */
    public static CharSequence formatAppNameAndSummary(String appName, String summary) {
        String toFormat = appName + ' ' + summary;
        CharacterStyle normal = new TypefaceSpan("sans-serif");
        CharacterStyle light = new TypefaceSpan("sans-serif-light");

        SpannableStringBuilder sb = new SpannableStringBuilder(toFormat);
        sb.setSpan(normal, 0, appName.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(light, appName.length(), toFormat.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        return sb;
    }

    /**
     * This is not strict validation of the package name, this is just to make
     * sure that the package name is not used as an attack vector, e.g. SQL
     * Injection.
     */
    public static boolean isSafePackageName(@Nullable String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        if (safePackageNamePattern == null) {
            safePackageNamePattern = Pattern.compile("[a-zA-Z0-9._]+");
        }
        return safePackageNamePattern.matcher(packageName).matches();
    }

    @NonNull
    public static String formatLastUpdated(@NonNull Resources res, @NonNull LocalDate localDate) {
        final Period period = Period.between(localDate, LocalDate.now());
        final int days = period.getDays();
        final int weeks = days / 7;
        final int months = period.getMonths();
        final int years = period.getYears();

        if (days < 1) {
            return res.getString(R.string.details_last_updated_today);
        } else if (weeks < 1) {
            return res.getQuantityString(R.plurals.details_last_update_days, days, days);
        } else if (months < 1) {
            return res.getQuantityString(R.plurals.details_last_update_weeks, weeks, weeks);
        } else if (years < 1) {
            return res.getQuantityString(R.plurals.details_last_update_months, months, months);
        } else {
            return res.getQuantityString(R.plurals.details_last_update_years, years, years);
        }
    }

    /**
     * Need this to add the unimplemented support for ordered and unordered
     * lists to Html.fromHtml().
     */
    public static class HtmlTagHandler implements Html.TagHandler {
        int listNum;

        @Override
        public void handleTag(boolean opening, String tag, Editable output,
                              XMLReader reader) {
            switch (tag) {
                case "ul":
                    if (opening) {
                        listNum = -1;
                    } else {
                        output.append('\n');
                    }
                    break;
                case "ol":
                    if (opening) {
                        listNum = 1;
                    } else {
                        output.append('\n');
                    }
                    break;
                case "li":
                    if (opening) {
                        if (listNum == -1) {
                            output.append("\t• ");
                        } else {
                            output.append("\t").append(Integer.toString(listNum)).append(". ");
                            listNum++;
                        }
                    } else {
                        output.append('\n');
                    }
                    break;
            }
        }
    }

    public static void debugLog(String tag, String msg) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, msg);
        }
    }

    public static void debugLog(String tag, String msg, Throwable tr) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, msg, tr);
        }
    }

    /**
     * Try to get the {@link PackageInfo#versionName} of the
     * client.
     *
     * @return null on failure
     */
    public static String getVersionName(Context context) {
        String versionName = null;
        try {
            versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not get client version name", e);
        }
        return versionName;
    }

    public static String getUserAgent() {
        return "F-Droid " + BuildConfig.VERSION_NAME;
    }

    /**
     * Try to get the {@link PackageInfo} for the {@code packageName} provided.
     *
     * @return null on failure
     */
    public static PackageInfo getPackageInfo(Context context, String packageName) {
        try {
            return context.getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            debugLog(TAG, "Could not get PackageInfo: ", e);
        }
        return null;
    }

    /**
     * Try to get the {@link PackageInfo} with signature info for the {@code packageName} provided.
     *
     * @return null on failure
     */
    @SuppressLint("PackageManagerGetSignatures")
    public static PackageInfo getPackageInfoWithSignatures(Context context, String packageName) {
        try {
            return context.getPackageManager().getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException e) {
            debugLog(TAG, "Could not get PackageInfo: ", e);
        }
        return null;
    }

    /**
     * Useful for debugging during development, so that arbitrary queries can be made, and their
     * results inspected in the debugger.
     */
    @SuppressWarnings("unused")
    @RequiresApi(api = 11)
    public static List<Map<String, String>> dumpCursor(Cursor cursor) {
        List<Map<String, String>> data = new ArrayList<>();

        if (cursor == null) {
            return data;
        }

        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            Map<String, String> row = new HashMap<>(cursor.getColumnCount());
            for (String col : cursor.getColumnNames()) {
                int i = cursor.getColumnIndex(col);
                switch (cursor.getType(i)) {
                    case Cursor.FIELD_TYPE_NULL:
                        row.put(col, null);
                        break;

                    case Cursor.FIELD_TYPE_INTEGER:
                        row.put(col, Integer.toString(cursor.getInt(i)));
                        break;

                    case Cursor.FIELD_TYPE_FLOAT:
                        row.put(col, Double.toString(cursor.getFloat(i)));
                        break;

                    case Cursor.FIELD_TYPE_STRING:
                        row.put(col, cursor.getString(i));
                        break;

                    case Cursor.FIELD_TYPE_BLOB:
                        row.put(col, new String(cursor.getBlob(i), Charset.defaultCharset()));
                        break;
                }
            }
            data.add(row);
            cursor.moveToNext();
        }

        cursor.close();
        return data;
    }

    public static int dpToPx(int dp, Context ctx) {
        Resources r = ctx.getResources();
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, r.getDisplayMetrics());
    }

    /**
     * Converts a {@code long} bytes value, like from {@link File#length()}, to
     * an {@code int} value that is kilobytes, suitable for things like
     * {@link android.widget.ProgressBar#setMax(int)} or
     * {@link androidx.core.app.NotificationCompat.Builder#setProgress(int, int, boolean)}
     */
    public static int bytesToKb(long bytes) {
        return (int) (bytes / 1024);
    }

    /**
     * Converts two {@code long} bytes values, like from {@link File#length()}, to
     * an {@code int} value that is a percentage, suitable for things like
     * {@link android.widget.ProgressBar#setMax(int)} or
     * {@link androidx.core.app.NotificationCompat.Builder#setProgress(int, int, boolean)}.
     * {@code total} must never be zero!
     */
    public static int getPercent(long current, long total) {
        return (int) ((100L * current + total / 2) / total);
    }

    @SuppressWarnings("unused")
    public static class Profiler {
        public final long startTime = System.currentTimeMillis();
        public final String logTag;

        public Profiler(String logTag) {
            this.logTag = logTag;
        }

        public void log(String message) {
            long duration = System.currentTimeMillis() - startTime;
            Utils.debugLog(logTag, "[" + duration + "ms] " + message);
        }
    }

    /**
     * In order to send a {@link Toast} from a {@link android.app.Service}, we
     * have to do these tricks.
     */
    public static void showToastFromService(final Context context, final String msg, final int length) {
        if (toastHandler == null) {
            toastHandler = new Handler(Looper.getMainLooper());
        }
        toastHandler.post(new Runnable() {

            @Override
            public void run() {
                Toast.makeText(context.getApplicationContext(), msg, length).show();
            }
        });
    }

    public static void applySwipeLayoutColors(SwipeRefreshLayout swipeLayout) {
        TypedValue typedValue = new TypedValue();
        Resources.Theme theme = swipeLayout.getContext().getTheme();
        theme.resolveAttribute(R.attr.colorPrimary, typedValue, true);
        swipeLayout.setColorSchemeColors(typedValue.data);
    }

    public static boolean canConnectToSocket(String host, int port) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5);
            socket.close();
            return true;
        } catch (IOException e) {
            // Could not connect.
            return false;
        }
    }

    public static boolean isServerSocketInUse(int port) {
        try {
            (new ServerSocket(port)).close();
            return false;
        } catch (IOException e) {
            // Could not connect.
            return true;
        }
    }

    @NonNull
    public static Single<Bitmap> generateQrBitmap(@NonNull final AppCompatActivity activity,
                                                  @NonNull final String qrData) {
        return Single.fromCallable(() -> {
            Display display = activity.getWindowManager().getDefaultDisplay();
            Point outSize = new Point();
            display.getSize(outSize);
            final int x = outSize.x;
            final int y = outSize.y;
            final int qrCodeDimension = Math.min(x, y);
            debugLog(TAG, "generating QRCode Bitmap of " + qrCodeDimension + "x" + qrCodeDimension);
            QRCodeEncoder qrCodeEncoder = new QRCodeEncoder(qrData, null,
                    Contents.Type.TEXT, BarcodeFormat.QR_CODE.toString(), qrCodeDimension);

            return qrCodeEncoder.encodeAsBitmap();
        })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(throwable -> Log.e(TAG, "Could not encode QR as bitmap", throwable));
    }

    /**
     * Keep an instance of this class as an field in an AppCompatActivity for figuring out whether the on
     * screen keyboard is currently visible or not.
     */
    public static class KeyboardStateMonitor {

        private boolean visible = false;

        /**
         * @param contentView this must be the top most Container of the layout used by the AppCompatActivity
         */
        public KeyboardStateMonitor(final View contentView) {
            contentView.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            int screenHeight = contentView.getRootView().getHeight();
                            Rect rect = new Rect();
                            contentView.getWindowVisibleDisplayFrame(rect);
                            int keypadHeight = screenHeight - rect.bottom;
                            visible = keypadHeight >= screenHeight * 0.15;
                        }
                    }
            );
        }

        public boolean isKeyboardVisible() {
            return visible;
        }
    }
}
