package org.fdroid.fdroid;

import android.app.Application;
import android.app.LocaleConfig;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.icu.util.ULocale;
import android.os.Build;
import android.os.LocaleList;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.ConfigurationCompat;
import androidx.core.os.LocaleListCompat;
import androidx.core.text.BidiFormatter;
import androidx.preference.ListPreference;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class Languages {
    public static final String TAG = "Languages";

    public static final String USE_SYSTEM_DEFAULT = "";

    /**
     * Denotes whether there is native per app language support.  Since {@link Build.VERSION_CODES#TIRAMISU}.
     */
    @ChecksSdkIntAtLeast(api = 33)
    public static final boolean NATIVE_PAL;

    /**
     * Denotes whether there is support for the {@link android.icu} package.  Since {@link Build.VERSION_CODES#N}.
     */
    @ChecksSdkIntAtLeast(api = 24)
    private static final boolean USE_ICU;

    private static LocaleListCompat systemLocales;
    private static LocaleListCompat lastLocaleList;
    private static AppLocale[] appLocales;
    private static AppLocale[] appResLocales;

    private static Locale defaultLocale;
    private static Locale locale;
    private static Languages singleton;

    private static final int CACHE = 0, RESOLVED = 1;
    private static final String[] LOCALE_SCRIPTS = new String[2];

    @SuppressWarnings("NoWhitespaceAfter")
    private static final char[] SCRIPT_HINTS = new char[] { '!', '#', '+', '-', '/' };
    private static final int NOT_PRESENT = 0, SCRIPT_SINGLE = 1,
            SCRIPT_SIGNIFICANT = 2, SCRIPT_INSIGNIFICANT = 3, STANDALONE = 4;

    static {
        USE_ICU = Build.VERSION.SDK_INT >= 24;
        NATIVE_PAL = Build.VERSION.SDK_INT >= 33;
        updateSystemLocales(null);
    }

    private Languages(@NonNull AppCompatActivity activity) {
        requireAppLocales(activity);
    }

    public static void onApplicationCreate(@NonNull final Application app) {
        if (NATIVE_PAL) return;
        if (lastLocaleList != null) {
            android.os.Handler handler = new android.os.Handler(app.getMainLooper());
            handler.post(() -> {
                updateConfiguration(app, lastLocaleList);
            });
        }
    }

    private static int compareStringSegments(@NonNull final String aString, int aPos, int aLen,
                                             @Nullable String bString, int bPos, int bLen) {
        int cmp = -1;
        if (bString == null) bString = aString;
        for (int i = 0, n = Math.min(aLen, bLen), al = aString.length(), bl = bString.length();
                i < n && aPos < al && bPos < bl; i++) {
            char charA = aString.charAt(aPos++);
            char charB = bString.charAt(bPos++);
            cmp = Character.compare(charA, charB);
            if (cmp != 0) break;
        }
        return cmp == 0 ? (aLen == bLen ? 0 : (aLen < bLen ? -1 : 1)) : (cmp < 0 ? -1 : 1);
    }

    private static int getStartOfLanguageRange(@NonNull final AppLocale[] appLocales,
                                               @NonNull final String appLang,
                                               final boolean useNewCode) {
        int i = -1, cmp = -1;
        do {
            cmp = appLang.compareToIgnoreCase(remapLegacyCode(appLocales[++i].getLanguage(),
                    useNewCode));
        } while (cmp > 0 && i < appLocales.length - 1);
        return cmp == 0 ? i : -1;
    }

    private static int getEndOfLanguageRange(@NonNull final AppLocale[] appLocales,
                                             @NonNull final String appLang,
                                             final int i, final boolean useNewCode) {
        int j = i + 1;
        while (j < appLocales.length) {
            if (!appLang.equalsIgnoreCase(remapLegacyCode(appLocales[j].getLanguage(),
                    useNewCode))) {
                break;
            }
            j++;
        }
        return j;
    }

    private static boolean isStopChar(final char c) {
        return searchSortedChars(SCRIPT_HINTS, c) >= 0;
    }

    private static class Commons {
        private static SparseArray<WeakReference> cache;
        public static final int AVAILABLE_LOCALES = 1;
        public static final int SYS_ASSETS_LOCALES = 2;
        public static final int APP_ASSETS_LOCALES = 3;

        @Nullable
        private static <T> T get(final int res, @NonNull final Class<T> c, @Nullable final Context context) {
            if (cache == null) cache = new SparseArray(2);
            WeakReference<T> ref = cache.get(res, null);
            Object result = ref == null ? null : ref.get();
            if (result == null) {
                switch (res) {
                    case AVAILABLE_LOCALES:
                        result = Locale.getAvailableLocales();
                        // Prior to AOSP's commit 92924f2 (Oct 2016; probably Android <8)
                        // https://cs.android.com/android/_/android/platform/libcore/+/92924f23a03635bb194b6481c4a950e6414ca4e4
                        // `Locale.getAvailableLocales()` queried `LocaleServiceProviderPool` which
                        // deduplicated `Locale`s with a `HashSet`.  Since then it directly fetches
                        // from `ICU.getAvailableLocales()` which deduplicates with a `LinkedHashSet`
                        // and the ordering appears to be quite stable (in ascending BCP-47 tags)
                        if (Build.VERSION.SDK_INT < 26) {
                            Arrays.sort((Locale[]) result, new Comparator<Locale>() {
                                @Override
                                public int compare(Locale o1, Locale o2) {
                                    return o1.toLanguageTag().compareTo(o2.toLanguageTag());
                                }
                            });
                        }
                        break;
                    case SYS_ASSETS_LOCALES:
                        result = Resources.getSystem().getAssets().getLocales();
                        break;
                    case APP_ASSETS_LOCALES:
                        if (context != null) result = context.getAssets().getLocales();
                        break;
                }
                if (result != null) cache.put(res, new WeakReference(result));
            }
            return c.isInstance(result) ? c.cast(result) : null;
        }

        @Nullable
        public static Locale[] getLocaleArray(final int res) {
            return get(res, Locale[].class, null);
        }

        @Nullable
        public static String[] getStringArray(final int res) {
            return get(res, String[].class, null);
        }

        @Nullable
        public static String[] getStringArray(final int res, @Nullable final Context context) {
            return get(res, String[].class, context);
        }
    }

    public static void updateCacheHint(@NonNull final Context context,
                                       @Nullable final SharedPreferences atStartTime) {
        final String cacheHintKey = "scripts-hint";
        // prefix a version number so that we can easily vitiate cache in case of future implementation changes
        final int schema = 0;
        String cached = atStartTime == null ? null : atStartTime.getString(cacheHintKey, null);
        String prefix = schema + ":" + Build.VERSION.SDK_INT + "=";
        if (cached != null && cached.startsWith(prefix)) {
            LOCALE_SCRIPTS[CACHE] = cached.substring(prefix.length());
        } else {
            processAppLocales(null, false, true, LOCALE_SCRIPTS);
            cached = LOCALE_SCRIPTS[CACHE];
            if (atStartTime != null && cached != null && !cached.isEmpty()) {
                atStartTime.edit().putString(cacheHintKey, prefix + cached).apply();
            }
        }
    }

    private static boolean showResLocales() {
        return BuildConfig.DEBUG && Preferences.get().expertMode();
    }

    @SuppressWarnings("NoWhitespaceAfter")
    private static void requireAppLocales(@NonNull final Context activity, final int mask) {
        if (LOCALE_SCRIPTS[CACHE] == null || LOCALE_SCRIPTS[CACHE].isEmpty()) {
            processAppLocales(null, false, true, LOCALE_SCRIPTS);
        }
        if (mask == 0) return;
        int[] echo = new int[] { appLocales == null && (mask & 1) != 0 ? 1 : 0,
                showResLocales() && appResLocales == null && (mask & (1 << 1)) != 0 ? 1 : 0 };
        AppLocale[][] results = prepareAppLocales(activity, echo);
        if (results != null && results.length == echo.length) {
            if (echo[0] == 1 && results[0] != null) {
                processAppLocales(results[0], true, true, LOCALE_SCRIPTS);
                appLocales = results[0];
            }
            if (echo[1] == 1 && results[1] != null) {
                processAppLocales(results[1], true, true, LOCALE_SCRIPTS);
                appResLocales = results[1];
            }
        }
    }

    private static void requireAppLocales(@NonNull final Context activity) {
        requireAppLocales(activity, showResLocales() ? 0b11 : 0b1);
    }

    private static void processAppLocales(@NonNull AppLocale[] appLocales,
                                          final boolean useCache, final boolean resolve,
                                          @Nullable final String[] localeScripts) {
        boolean dryRun = appLocales == null;
        boolean genericHints = dryRun;
        int fallbackOp = SCRIPT_INSIGNIFICANT;
        Locale[] sysLocales = dryRun ? Commons.getLocaleArray(Commons.AVAILABLE_LOCALES) : null;
        int[] cachePos = null, defaultOp = null;
        StringBuilder sb = dryRun ? new StringBuilder().append(SCRIPT_HINTS[fallbackOp]) : null;
        int i = 0, j = -1, k = -1, langLocales = 0, langScripts = 0;
        String script = "";
        int end = dryRun ? sysLocales.length : appLocales.length;
        while (i < end) {
            String appLang = remapLegacyCode(dryRun ? sysLocales[i].getLanguage()
                    : appLocales[i].getLanguage());
            j = dryRun ? i : getEndOfLanguageRange(appLocales, appLang, i, true);
            if (j > i + 1) {
                for (int m = i; m < j; m++) {
                    AppLocale checkLocale = appLocales[m];
                    int checkMask = checkLocale.flags & (LOCALE << IMPUTED_OFFSET);
                    if (checkMask == 0) continue;
                    String checkScript = checkLocale.getScript(true);
                    String checkCountry = checkLocale.getCountry(true);
                    for (int n = m; n < j; n++) {
                        if (n == m) continue;
                        AppLocale next = appLocales[n];
                        if (checkScript.equalsIgnoreCase(next.getScript(true))) {
                            if ((checkMask & IMPUTED_COUNTRY) != 0
                                    && checkCountry.equalsIgnoreCase(next.getCountry())) {
                                checkLocale.flags |= DISCOUNT_COUNTRY;
                                break;
                            } else if ((checkMask & IMPUTED_SCRIPT) != 0
                                    && next.getCountry().isEmpty()) {
                                checkLocale.flags |= DISCOUNT_SCRIPT;
                                break;
                            }
                        }
                    }
                }
            }

            boolean handled = false;
            if (useCache && LOCALE_SCRIPTS != null && LOCALE_SCRIPTS.length > 0) {
                if (cachePos == null) {
                    cachePos = new int[LOCALE_SCRIPTS.length];
                    Arrays.fill(cachePos, -1);
                }
                if (defaultOp == null) {
                    defaultOp = new int[LOCALE_SCRIPTS.length];
                    Arrays.fill(defaultOp, -1);
                }
                for (int c = 0; c < LOCALE_SCRIPTS.length; c++) {
                    String cache = LOCALE_SCRIPTS[c];
                    if (cache == null || cache.isEmpty()) continue;
                    if (cachePos[c] < 0) {
                        int headerLen = 0;
                        for (int a = 0; a < cache.length(); a++) {
                            char op = cache.charAt(a);
                            if (isStopChar(op)) headerLen++;
                            else break;
                        }
                        if (headerLen > 0) {
                            int cmp = searchSortedChars(SCRIPT_HINTS, cache.charAt(headerLen - 1));
                            if (cmp >= 0) defaultOp[c] = cmp;
                        }
                        cachePos[c] = headerLen;
                    }
                    int pos = cachePos[c];

                    int len = appLang.length();
                    int cmp = -1, opPos = -1;
                    do {
                        int start = -1, segLen = 0;
                        for (int p = pos; p < cache.length(); p++) {
                            boolean stopChar = isStopChar(cache.charAt(p));
                            if (start < 0) {
                                if (!stopChar) {
                                    start = pos;
                                    segLen++;
                                }
                            } else {
                                if (!stopChar) segLen++;
                                else break;
                            }
                        }
                        cmp = compareStringSegments(appLang, 0, len, cache, start, segLen);
                        if (cmp > 0) pos = start + segLen + 1;
                    } while (cmp > 0 && pos < cache.length());

                    if (cmp == 0 && cache.length() > pos + len) {
                        opPos = pos + len;
                    }
                    cachePos[c] = pos;
                    boolean validOp = opPos >= 0 && isStopChar(cache.charAt(opPos));
                    if (validOp || defaultOp[c] != -1) {
                        char op = validOp ? cache.charAt(opPos) : SCRIPT_HINTS[defaultOp[c]];
                        for (int l = i; l < j; l++) {
                            AppLocale appLocale = appLocales[l];
                            appLocale.flags |= MATCHSYS_CACHED;
                            if (op == SCRIPT_HINTS[SCRIPT_SIGNIFICANT]) {
                                appLocale.flags |= MATCHSYS_SCRIPT;
                            } else if (op == SCRIPT_HINTS[STANDALONE]
                                    && (appLocale.flags & COUNTRY) == 0) {
                                appLocale.flags |= DISCOUNT_COUNTRY;
                            }
                        }
                        if (validOp) cachePos[c] = opPos + 1;
                        handled = true;
                        break;
                    }
                }
            }

            if (!handled && resolve) {
                if (sb == null) sb = new StringBuilder(4 * appLocales.length);
                if (sysLocales == null) sysLocales = Commons.getLocaleArray(Commons.AVAILABLE_LOCALES);

                String sysLang;
                int compare = -1;
                do {
                    sysLang = remapLegacyCode(sysLocales[++k].getLanguage());
                    compare = appLang.compareToIgnoreCase(sysLang);
                } while (compare > 0 && k < sysLocales.length - 1);
                if (compare == 0) {
                    for (int n = sysLocales.length - 1; k <= n; k++) {
                        Locale sysLocale = sysLocales[k];
                        if (!sysLocale.getVariant().isEmpty()) continue;
                        langLocales++;
                        String sysScript = sysLocale.getScript();
                        if (!sysScript.isEmpty() && !script.equalsIgnoreCase(sysScript)) {
                            langScripts++;
                            script = sysScript;
                        }
                        for (int l = i; l < j; l++) {
                            AppLocale appLocale = appLocales[l];
                            int cmp = compare(sysLocale, appLocale);
                            if (isMarked(cmp, COUNTRY | SCRIPT)) {
                                appLocale.flags |= SYSPRESENT_EXACT;
                                appLocale.sysLocale = sysLocale;
                            } else if (isMarked(cmp, COUNTRY | IMPUTED_SCRIPT)) {
                                if ((appLocale.flags & DISCOUNT_COUNTRY) == 0) {
                                    appLocale.flags |= SYSPRESENT_SCRIPT;
                                    appLocale.sysLocale = sysLocale;
                                }
                            }
                            if (isMarked(cmp, U_COUNTRY | U_SCRIPT)
                                    || isMarked(cmp, U_COUNTRY | IMPUTED_SCRIPT)) {
                                if ((appLocale.flags & DISCOUNT_COUNTRY) == 0) {
                                    appLocale.flags |= SYSPRESENT_SCRIPT;
                                    appLocale.sysLocale = sysLocale;
                                }
                            }
                        }
                        if (k < n && !appLang.equalsIgnoreCase(
                                remapLegacyCode(sysLocales[k + 1].getLanguage()))) {
                            break;
                        }
                    }
                    if (!appLang.isEmpty()) {
                        int op = langLocales == 1 ? STANDALONE
                                : (langScripts > 1 ? SCRIPT_SIGNIFICANT
                                : (langScripts == 1 ? SCRIPT_SINGLE : SCRIPT_INSIGNIFICANT));
                        if (!genericHints || op != fallbackOp) {
                            sb.append(appLang);
                            sb.append(SCRIPT_HINTS[op]);
                        }
                    }
                    script = "";
                    langLocales = 0;
                    langScripts = 0;
                } else {
                    if (!appLang.isEmpty()) {
                        sb.append(appLang);
                        sb.append(SCRIPT_HINTS[NOT_PRESENT]);
                    }
                }
            }

            i = dryRun ? k + 1 : j;
        }
        if (sb != null && sb.length() > 0 && localeScripts != null) {
            int pos = dryRun ? CACHE : RESOLVED;
            if (localeScripts.length > pos) localeScripts[pos] = sb.toString();
        }
    }

    /**
     * @param activity the {@link AppCompatActivity} this is working as part of
     * @return the singleton to work with
     */
    @NonNull
    public static Languages get(@NonNull final AppCompatActivity activity) {
        if (singleton == null) {
            singleton = new Languages(activity);
        }
        return singleton;
    }

    public static void updateSystemLocales(@Nullable final Configuration config) {
        LocaleListCompat newLocales = null;
        if (config != null) {
            newLocales = ConfigurationCompat.getLocales(config);
        }
        if (newLocales != null && !newLocales.isEmpty()) {
            defaultLocale = Locale.getDefault();
            systemLocales = newLocales;
        } else {
            defaultLocale = Locale.getDefault();
            systemLocales = LocaleListCompat.getDefault();
        }
        locale = null;
        lastLocaleList = null;
    }

    private static Locale.Builder localeBuilder;

    @NonNull
    private static Locale.Builder getBuilder() {
        if (localeBuilder == null) {
            localeBuilder = new Locale.Builder();
        } else {
            localeBuilder.clear();
        }
        return localeBuilder;
    }

    private static final int LOCALE = 0b111;
    private static final int LANG = 1;
    private static final int SCRIPT = 1 << 1;
    private static final int COUNTRY = 1 << 2;

    private static final int U_OFFSET = 4;
    private static final int U_LANG = LANG << U_OFFSET;
    private static final int U_SCRIPT = SCRIPT << U_OFFSET;
    private static final int U_COUNTRY = COUNTRY << U_OFFSET;

    private static final int IMPUTED_OFFSET = 8;
    private static final int IMPUTED_LANG = LANG << IMPUTED_OFFSET;
    private static final int IMPUTED_SCRIPT = SCRIPT << IMPUTED_OFFSET;
    private static final int IMPUTED_COUNTRY = COUNTRY << IMPUTED_OFFSET;

    private static final int DISCOUNT_OFFSET = 12;
    private static final int DISCOUNT_SCRIPT = SCRIPT << DISCOUNT_OFFSET;
    private static final int DISCOUNT_COUNTRY = COUNTRY << DISCOUNT_OFFSET;

    private static final int MATCHSYS_OFFSET = 16;
    private static final int MATCHSYS_CACHED = 1 << MATCHSYS_OFFSET;
    private static final int MATCHSYS_SCRIPT = SCRIPT << MATCHSYS_OFFSET;

    private static final int SYSPRESENT_OFFSET = 20;
    private static final int SYSPRESENT_EXACT = 1 << SYSPRESENT_OFFSET;
    private static final int SYSPRESENT_SCRIPT = SCRIPT << SYSPRESENT_OFFSET;

    private static class AppLocale implements Comparable<AppLocale> {
        @NonNull Locale locale;
        Locale sysLocale = null;
        int flags = 0;

        AppLocale(@NonNull final Locale locale) {
            this.locale = locale;
            setParts();
        }

        private void setParts() {
            if (!locale.getLanguage().isEmpty()) flags |= LANG;
            if (!locale.getScript().isEmpty()) flags |= SCRIPT;
            if (!locale.getCountry().isEmpty()) flags |= COUNTRY;
        }

        public String getLanguage() {
            return locale.getLanguage();
        }

        public String getScript() {
            return getScript(false);
        }

        public String getScript(final boolean impute) {
            return locale.getScript();
        }

        public String getCountry() {
            return getCountry(false);
        }

        public String getCountry(final boolean impute) {
            return locale.getCountry();
        }

        @NonNull
        public Locale getMatchingSystemLocale() {
            return sysLocale == null ? locale : sysLocale;
        }

        @Override
        public String toString() {
            return locale.toLanguageTag();
        }

        @Override
        public int compareTo(AppLocale another) {
            int compare = 0;
            String a = "", b = "";
            for (int i = 0; i < 3; i++) {
                for (int v = 0; v < 2; v++) {
                    String s = "";
                    AppLocale o = (v == 0) ? this : another;
                    if (i == 0) {
                        s = remapLegacyCode(o.getLanguage());
                    } else if (i == 1) {
                        s = o.getScript(true);
                    } else if (i == 2) {
                        s = o.getCountry();
                    }
                    if (v == 0) {
                        a = s;
                    } else if (v == 1) {
                        b = s;
                    }
                }
                compare = a.compareToIgnoreCase(b);
                if (compare != 0) break;
            }
            return (compare < 0) ? -1 : (compare > 0) ? 1 : 0;
        }
    }

    @RequiresApi(api = 24)
    private static class AppLocaleIcu extends AppLocale {
        @NonNull ULocale icuLocale;

        AppLocaleIcu(@NonNull final Locale locale) {
            super(locale);
            icuLocale = ULocale.addLikelySubtags(ULocale.forLocale(locale));
            setParts();
        }

        private void setParts() {
            String uL = icuLocale.getLanguage();
            String uS = icuLocale.getScript();
            String uC = icuLocale.getCountry();
            if (!uL.isEmpty()) {
                flags |= U_LANG;
                if (!uL.equalsIgnoreCase(locale.getLanguage())) flags |= IMPUTED_LANG;
            }
            if (!uS.isEmpty()) {
                flags |= U_SCRIPT;
                if (!uS.equalsIgnoreCase(locale.getScript())) flags |= IMPUTED_SCRIPT;
            }
            if (!uC.isEmpty()) {
                flags |= U_COUNTRY;
                if (!uC.equalsIgnoreCase(locale.getCountry())) flags |= IMPUTED_COUNTRY;
            }
        }

        @Override
        public String getScript(final boolean impute) {
            String script = locale.getScript();
            return (script.isEmpty() && impute) ? icuLocale.getScript() : script;
        }

        @Override
        public String getCountry(final boolean impute) {
            String country = locale.getCountry();
            return (country.isEmpty() && impute) ? icuLocale.getCountry() : country;
        }

        @NonNull
        @Override
        public Locale getMatchingSystemLocale() {
            if (sysLocale != null) {
                return sysLocale;
            }
            if ((flags & MATCHSYS_CACHED) != 0) {
                Locale.Builder builder = getBuilder().setLanguage(icuLocale.getLanguage());
                if ((flags & DISCOUNT_SCRIPT) == 0 && ((flags & MATCHSYS_SCRIPT) != 0
                        || isMarked(flags, SCRIPT | IMPUTED_SCRIPT))) {
                    if ((flags & SCRIPT) != 0) {
                        builder.setScript(locale.getScript());
                    } else if ((flags & U_SCRIPT) != 0) {
                        builder.setScript(icuLocale.getScript());
                    }
                }
                if ((flags & DISCOUNT_COUNTRY) == 0) {
                    if ((flags & COUNTRY) != 0) {
                        builder.setRegion(locale.getCountry());
                    } else if ((flags & U_COUNTRY) != 0) {
                        builder.setRegion(icuLocale.getCountry());
                    }
                }
                sysLocale = builder.build();
                return sysLocale;
            }
            return locale;
        }

    }

    @NonNull
    private static AppLocale createAppLocale(@NonNull final Locale locale) {
        return USE_ICU ? new AppLocaleIcu(locale) : new AppLocale(locale);
    }

    private static int compare(@NonNull final Locale sysLocale, @NonNull final AppLocale appLocale) {
        int flags = 0;
        String l = remapLegacyCode(sysLocale.getLanguage());
        String s = sysLocale.getScript();
        String c = sysLocale.getCountry();
        if (l.equalsIgnoreCase(remapLegacyCode(appLocale.getLanguage()))) flags |= LANG;
        if (s.equalsIgnoreCase(appLocale.getScript())) flags |= SCRIPT;
        if (c.equalsIgnoreCase(appLocale.getCountry())) flags |= COUNTRY;
        if (USE_ICU && appLocale instanceof AppLocaleIcu) {
            AppLocaleIcu appLocaleIcu = (AppLocaleIcu) appLocale;
            if ((appLocaleIcu.flags & IMPUTED_LANG) != 0
                    && l.equalsIgnoreCase(remapLegacyCode(appLocaleIcu.icuLocale.getLanguage()))) {
                flags |= U_LANG;
            }
            if ((appLocaleIcu.flags & IMPUTED_SCRIPT) != 0
                    && s.equalsIgnoreCase(appLocaleIcu.icuLocale.getScript())) {
                flags |= U_SCRIPT;
            }
            if ((appLocaleIcu.flags & IMPUTED_COUNTRY) != 0
                    && c.equalsIgnoreCase(appLocaleIcu.icuLocale.getCountry())) {
                flags |= U_COUNTRY;
            }
        }
        if ((flags & LANG) != 0) {
            if ((flags & (COUNTRY | U_COUNTRY)) != 0
                    && (s.isEmpty() || (flags & (SCRIPT | U_SCRIPT)) != 0)) {
                flags |= IMPUTED_SCRIPT;
            }
        }
        return flags;
    }

    private static boolean isMarked(final int i, final int flags) {
        return (i & flags) == flags;
    }

    /**
     * Map obsolete language codes to new language codes
     * @see <a href="https://developer.android.com/reference/java/util/Locale#legacy-language-codes">Legacy language codes</a> mapped by Locale
     */
    @SuppressWarnings("NoWhitespaceAfter")
    private static final String[] OLD_LANG_CODES = { "in", "iw", "ji" };
    @SuppressWarnings("NoWhitespaceAfter")
    private static final String[] NEW_LANG_CODES = { "id", "he", "yi" };

    private static int searchSortedStrings(@NonNull final String[] haystack, @NonNull final String needle) {
        int i = -1, cmp = -1;
        do {
            cmp = needle.compareTo(haystack[++i]);
        } while (cmp > 0 && i < haystack.length - 1);
        return cmp == 0 ? i : -1;
    }

    private static int searchSortedChars(@NonNull final char[] haystack, final char needle) {
        int i = -1, cmp = -1;
        do {
            cmp = Character.compare(needle, haystack[++i]);
        } while (cmp > 0 && i < haystack.length - 1);
        return cmp == 0 ? i : -1;
    }

    @NonNull
    private static String remapLegacyCode(@NonNull final String lang) {
        int index = searchSortedStrings(OLD_LANG_CODES, lang);
        return index >= 0 ? NEW_LANG_CODES[index] : lang;
    }

    @NonNull
    private static String remapLegacyCode(@NonNull final String lang, final boolean enable) {
        return enable ? remapLegacyCode(lang) : lang;
    }

    private static void setLocaleListDefault(@NonNull final LocaleListCompat newLocales) {
        if (Build.VERSION.SDK_INT >= 24) {
            LocaleList.setDefault((LocaleList) newLocales.unwrap());
        } else {
            Locale.setDefault(locale);
        }
    }

    @SuppressWarnings("NoWhitespaceAfter")
    private static AppLocale matchAppLocale(@NonNull final Context context, @Nullable final String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return null;
        Locale l = Locale.forLanguageTag(languageTag);
        String lang = remapLegacyCode(l.getLanguage());
        for (int a = 0, n = showResLocales() ? 2 : 1; a < n; a++) {
            requireAppLocales(context, 1 << a);
            AppLocale[] locales = a == 0 ? appLocales : appResLocales;
            if (locales == null) continue;
            int i = getStartOfLanguageRange(locales, lang, true);
            if (i < 0) continue;
            int j = getEndOfLanguageRange(locales, lang, i, true);
            if (j > locales.length) continue;
            for (; i < j; i++) {
                AppLocale appLocale = locales[i];
                if (l.equals(appLocale.locale) || l.equals(appLocale.getMatchingSystemLocale())) {
                    return appLocale;
                }
            }
        }
        return null;
    }

    private static void updateConfiguration(@NonNull final Context context,
                                            @NonNull final LocaleListCompat newLocales) {
        Log.d(TAG, "Updating resources configuration for " + context.toString());
        final Resources resources = context.getResources();
        Configuration config = new Configuration(resources.getConfiguration());
        Log.d(TAG, "Old locales: " + ConfigurationCompat.getLocales(config).toString());
        ConfigurationCompat.setLocales(config, newLocales);
        resources.updateConfiguration(config, resources.getDisplayMetrics());
        Log.d(TAG, "New locales: " + ConfigurationCompat.getLocales(config).toString());
    }

    /**
     * AOSP commit <a href="https://android.googlesource.com/platform/frameworks/base/+/fc8c211b436aa180818780a6ade107ad30835ef8">fc8c211</a>
     * "Accept repeated locale as an input of LocaleList construction."
     * landed into {@link LocaleList} in Sep 2020.  Unfortunately {@link LocaleListCompat}
     * doesn't cover the edge case for us and simply hands it off to platform implementation where available.
     * In other words, we cannot rely on the constructor (to silently drop duplicates) but to do the
     * deduplication on our own (lest IAE will be thrown on older Android versions (presumably <11)).
     *
     * @param preferred The {@link Locale} to be placed at the top of the {@link LocaleList}.
     * @param baseList The {@link LocaleList} to base on for adjustment.
     * @return the adjusted {@link LocaleList}
     */
    @Nullable
    private static LocaleListCompat adjustLocaleList(@Nullable final Locale preferred,
                                                     @Nullable final LocaleListCompat baseList) {
        if (preferred == null) return baseList;
        if (baseList == null) return LocaleListCompat.create(preferred);
        int pos = baseList.indexOf(preferred);
        if (pos == 0) return baseList;
        int adj = pos == -1 ? 1 : 0;
        Locale[] locales = new Locale[baseList.size() + adj];
        locales[0] = preferred;
        for (int i = 1, n = locales.length; i < n; i++) {
            locales[i] = baseList.get(i - (i <= pos ? 1 : adj));
        }
        return LocaleListCompat.create(locales);
    }

    /**
     * Handles setting the language if it is different than the current language,
     * or different than the current system-wide locale.
     */
    public static void setLanguage(@NonNull final Context context) {
        if (!Preferences.get().isLanguageSet()) {
            locale = defaultLocale;
            return;
        }
        setLanguage(context, Preferences.get().getLanguage());
    }

    public static void setLanguage(@NonNull final Context context, @Nullable final String language) {
        boolean changed = locale == null;
        if (language == null || language.equals(USE_SYSTEM_DEFAULT)) {
            if (NATIVE_PAL && locale == null) return;
            changed = locale != null;
            locale = defaultLocale;
        } else {
            AppLocale appLocale = matchAppLocale(context, language);
            Locale sysLocale = appLocale == null ? Locale.forLanguageTag(language)
                    : appLocale.getMatchingSystemLocale();
            if (locale != null && sysLocale.equals(locale)) {
                return; // already configured
            } else if (sysLocale.equals(defaultLocale)) {
                changed = locale != null;
                locale = defaultLocale;
            } else {
                changed = true;
                locale = sysLocale;
            }
        }

        if (changed) {
            LocaleListCompat newLocales = adjustLocaleList(locale, systemLocales);
            lastLocaleList = newLocales;

            updateConfiguration(context.getApplicationContext(), newLocales);

            AppCompatDelegate.setApplicationLocales(language.equals(USE_SYSTEM_DEFAULT)
                    ? LocaleListCompat.getEmptyLocaleList() : LocaleListCompat.create(locale));

            if (NATIVE_PAL) {
                Preferences.get().clearLanguage();
            } else {
                setLocaleListDefault(newLocales);
            }
        }
    }

    /**
     * Force reload the {@link AppCompatActivity} to make language changes take effect.
     *
     * @param activity the {@code AppCompatActivity} to force reload
     */
    public static void forceChangeLanguage(@NonNull AppCompatActivity activity) {
        if (!NATIVE_PAL) {
            // Cherry-picked from AOSP commit 9752b73 included in AppCompat 1.7.0-alpha02:
            // https://android.googlesource.com/platform/frameworks/support/+/9752b7383244c2ab548970d89a257ef368183b88
            // "To workaround the android framework issue(b/242026447) which doesn't update the
            // layout direction after recreating in Android S." (adapted with modification).
            if (Build.VERSION.SDK_INT >= 31
                    && activity.getResources().getConfiguration().getLayoutDirection() !=
                    TextUtils.getLayoutDirectionFromLocale(locale)) {
                View view = activity.getWindow().getDecorView();
                view.setLayoutDirection(TextUtils.getLayoutDirectionFromLocale(locale));
            }
        }
        // `AppCompatDelegate` would take care to recreate `AppCompatActivity`s as necessary
    }

    /**
     * @return an array of the names of all the supported languages, sorted to
     * match what is returned by {@link Languages#getSupportedLocales()}.
     */
    @NonNull
    public static String[] getAllNames(@NonNull final Context context) {
        return mapToArray(context, false, NATIVE_PAL);
    }

    /**
     * @return sorted list of supported locales.
     */
    @NonNull
    public static String[] getSupportedLocales(@NonNull final Context context) {
        return mapToArray(context, true, NATIVE_PAL);
    }

    @NonNull
    private static String capitalize(@NonNull final String line, @Nullable final Locale displayLocale) {
        if (line.isEmpty()) return line;
        String firstChar = displayLocale == null || displayLocale.getLanguage().isEmpty()
                ? Character.toString(Character.toUpperCase(line.charAt(0)))
                : line.substring(0, 1).toUpperCase(displayLocale);
        return firstChar.charAt(0) == line.charAt(0) ? line
                : (line.length() == 1 ? firstChar : firstChar + line.substring(1));
    }

    @NonNull
    private static String[] mapToArray(@NonNull final Context context, final boolean key,
                                       final boolean matchSystemLocales, final boolean sortTogether,
                                       @Nullable final BidiFormatter bidi) {
        int resLocales = showResLocales() && appResLocales != null ? appResLocales.length : 0;
        String[] names = new String[appLocales.length + 1 + resLocales];
        final String label = "\uD83D\uDEA7"; // 🚧
        /* SYSTEM_DEFAULT is a fake one for displaying in a chooser menu. */
        names[0] = key ? USE_SYSTEM_DEFAULT : context.getString(R.string.pref_language_default);
        int i = 0, j = 0, k = 1, cmp = -1, o = names.length;
        StringBuilder sb = new StringBuilder();
        for (int n = appLocales.length, m = resLocales; k < o;) {
            cmp = (sortTogether && i < n && j < m) ? appLocales[i].compareTo(appResLocales[j])
                    : (i < n ? -1 : 1);
            AppLocale appLocale = cmp <= 0 ? appLocales[i++] : appResLocales[j++];
            Locale locale = matchSystemLocales ? appLocale.getMatchingSystemLocale() : appLocale.locale;
            int addLabel = sortTogether ? 1 : -1;
            names[k++] = key ? locale.toLanguageTag()
                    : getDisplayName(appLocale.locale, cmp > 0 && addLabel < 0 ? label : null,
                    cmp > 0 && addLabel > 0 ? label : null, "\u2007", bidi, sb);
            if (cmp == 0 && k < o) {
                j++;
                o--;
            }
        }
        return o == names.length ? names : Arrays.copyOf(names, o);
    }

    @NonNull
    private static String[] mapToArray(@NonNull final Context context, final boolean key,
                                       final boolean matchSystemLocales) {
        return mapToArray(context, key, matchSystemLocales, true, BidiFormatter.getInstance());
    }

    /**
     * aligns with AOSP's {@link com.android.internal.app.LocaleHelper#shouldUseDialectName}
     */
    @SuppressWarnings("NoWhitespaceAfter")
    private static final String[] DIALECT_LANGS = { "fa", "ro", "zh" };

    private static boolean showDialect(@NonNull final String lang) {
        return USE_ICU && !lang.isEmpty() && searchSortedStrings(DIALECT_LANGS, lang) >= 0;
    }

    @RequiresApi(api = 24)
    private static String getDisplayNameWithDialect(@NonNull final Locale locale,
                                                    @NonNull final Locale displayLocale) {
        return ULocale.forLocale(locale).getDisplayNameWithDialect(ULocale.forLocale(displayLocale));
    }

    @Nullable
    private static String bidiWrap(@Nullable final String string, @Nullable final BidiFormatter bidi) {
        return string == null ? null : (bidi == null ? string : bidi.unicodeWrap(string));
    }

    @Nullable
    private static String getDisplayName(@Nullable final Locale locale, @Nullable final String prefix,
                                         @Nullable final String suffix, @Nullable final String sep,
                                         @Nullable final BidiFormatter bidi, @Nullable StringBuilder sb) {
        if (locale == null) return null;
        final String lang = locale.getLanguage();
        final int langLen = lang.length();
        final boolean showDialect = showDialect(lang);
        String name = null;
        LocaleListCompat locales = null;
        int i = -1, n = 0;
        Locale displayLocale = locale;
        boolean resolved = true;
        while (i <= n + 1 && (name == null || name.isEmpty() || (name.startsWith(lang)
                && (name.length() > langLen ? name.charAt(langLen) == ' ' : true)))) {
            if (i == 0 && locales == null) {
                locales = LocaleListCompat.getDefault();
                n = locales.size();
            }
            if (i == n + 1 && name != null && !name.isEmpty()) {
                resolved = false;
                break;
            }
            if (i >= 0) displayLocale = i < n ? locales.get(i) : Locale.ENGLISH;

            name = showDialect ? getDisplayNameWithDialect(locale, displayLocale)
                    : locale.getDisplayName(displayLocale);
            i++;
        }
        if (name == null || name.isEmpty()) return name;
        int prefixLen = prefix == null ? 0 : prefix.length(), suffixLen = suffix == null ? 0 : suffix.length(),
                sepLen = sep == null ? 0 : sep.length();
        if (sb != null) {
            if (sb.length() > 0) sb.setLength(0);
        } else sb = new StringBuilder((resolved ? 0 : 3) + name.length() + prefixLen + suffixLen
                + sepLen * ((prefixLen > 0 ? 1 : 0) + (suffixLen > 0 ? 1 : 0))
                + (bidi == null ? 0 : 10)); // reserve some headroom for directionality characters
        if (prefixLen > 0) sb.append(bidiWrap(prefix, bidi)).append(sep);
        int comma = resolved ? sb.length() : -1;
        if (!resolved) sb.append("\uD83C\uDF10").append('\u2009'); // 🌐
        sb.append(bidiWrap(resolved ? capitalize(name, displayLocale) : name, bidi));
        if (resolved) comma = sb.indexOf(",", comma);
        // nitpick: give it the benefit of a space if there isn't one after the comma (separating script and country)
        if (comma > 0 && comma < sb.length() - 2 && sb.charAt(comma + 1) != ' ') sb.insert(comma + 1, ' ');
        if (suffixLen > 0) sb.append(sep).append(bidiWrap(suffix, bidi));
        return sb.toString();
    }

    public static String getCurrentLocale() {
        return getDisplayName(Locale.getDefault(), null, null, null, null, null);
    }

    public static String getAppLocale() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        return locales.isEmpty() ? USE_SYSTEM_DEFAULT : locales.get(0).toLanguageTag();
    }

    private static WeakReference<ListPreference> langPreference;

    private static void updateListPreference(@Nullable final ListPreference languagePref) {
        if (languagePref == null) return;
        languagePref.setEntries(getAllNames(languagePref.getContext()));
        languagePref.setEntryValues(getSupportedLocales(languagePref.getContext()));
    }

    public static void updateListPreference() {
        if (langPreference != null) updateListPreference(langPreference.get());
    }

    public static void updateListPreference(@Nullable final ListPreference languagePref,
                                            @NonNull final Context activity) {
        requireAppLocales(activity);
        updateListPreference(languagePref);
    }

    public static void bindListPreference(@NonNull final ListPreference languagePref, boolean onCreate) {
        langPreference = new WeakReference(languagePref);
    }

    @NonNull
    public static Locale[] toLocales(@NonNull final String[] locales) {
        Arrays.sort(locales);
        Locale[] array = new Locale[locales.length];
        for (int i = 0; i < locales.length; i++) {
            array[i] = Locale.forLanguageTag(locales[i]);
        }
        return array;
    }

    @NonNull
    public static AppLocale[] toAppLocales(@NonNull final String[] locales) {
        AppLocale[] array = new AppLocale[locales.length];
        for (int i = 0; i < locales.length; i++) {
            array[i] = createAppLocale(Locale.forLanguageTag(locales[i]));
        }
        return array;
    }

    @Nullable
    private static String[] fetchAppLocales(@NonNull final Context activity, @Nullable final String lang) {
        String[] appLocales = null;
        if (Build.VERSION.SDK_INT >= 33) {
            // `LocaleConfig` deduplicates with a `HashSet` which leaves us with the (eventual) sorting on our own
            LocaleConfig localeConfig = new LocaleConfig(activity);
            LocaleList locales = localeConfig.getSupportedLocales();
            if (locales != null) {
                if (lang == null || lang.isEmpty()) {
                    appLocales = new String[locales.size()];
                    for (int i = 0; i < locales.size(); i++) {
                        appLocales[i] = locales.get(i).toLanguageTag();
                    }
                } else {
                    ArrayList<String> langLocales = new ArrayList<>(locales.size());
                    for (int i = 0; i < locales.size(); i++) {
                        Locale l = locales.get(i);
                        if (remapLegacyCode(l.getLanguage()).equals(lang)) {
                            langLocales.add(l.toLanguageTag());
                        }
                    }
                    if (langLocales.isEmpty()) return null;
                    else appLocales = langLocales.toArray(new String[0]);
                }
            }
        }
        if (appLocales == null) appLocales = parseXMLLocales(activity.getResources(), lang);
        if (appLocales != null) Arrays.sort(appLocales);
        return appLocales;
    }

    @Nullable
    private static String[] fetchAppLocales(@NonNull final Context activity) {
        return fetchAppLocales(activity, null);
    }

    @Nullable
    public static String[] parseXMLLocales(@NonNull final Resources resources, @Nullable final String lang) {
        Map<String, String> locales = new TreeMap<>();
        parseXMLResource(resources, R.xml.locales_config, locales, "locale", 0, lang);
        return locales.isEmpty() ? null : locales.keySet().toArray(new String[0]);
    }

    @Nullable
    public static AppLocale[][] prepareAppLocales(@NonNull final Context context, @NonNull int[] echo) {
        if (echo.length != 2) return null;
        AppLocale[][] results = new AppLocale[echo.length][];
        Context appContext = context.getApplicationContext();
        String[] appLocales = (echo[0] > 0 || echo[1] > 0) ? fetchAppLocales(appContext) : null;
        if (appLocales == null) return null;
        if (echo[0] > 0) {
            echo[0] = 1;
            results[0] = toAppLocales(appLocales);
        }
        if (echo[1] > 0) {
            String[] localesArray = null;
            try {
                localesArray = (String[]) BuildConfig.class.getField("RES_LOCALES").get(BuildConfig.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (localesArray != null) {
                echo[1] = 1;
                results[1] = toAppLocales(localesArray);
            }
        }
        return results;
    }

    private static void parseXMLResource(@NonNull final Resources resources, int resId,
                                         @NonNull final Map<String, String> results,
                                         @Nullable final String matchTag, final int getAttribute,
                                         @Nullable final String lang) {
        try (XmlResourceParser parser = resources.getXml(resId)) {
            String key = null, value = null;
            int eventType = parser.getEventType();
            while (eventType != XmlResourceParser.END_DOCUMENT) {
                if (eventType == XmlResourceParser.START_TAG) {
                    if (matchTag == null || matchTag.isEmpty()
                            || parser.getName().equalsIgnoreCase(matchTag)) {
                        String s = parser.getAttributeValue(getAttribute >= 0 ? getAttribute : 0);
                        if (lang == null || lang.isEmpty() || (s.startsWith(lang)
                                && (s.length() == lang.length() || s.charAt(lang.length()) == '-'))) {
                            key = s;
                        }
                    }
                } else if (key != null && eventType == XmlResourceParser.TEXT) {
                    value = parser.getText();
                }
                if (key != null && ((eventType == XmlResourceParser.START_TAG
                        && parser.isEmptyElementTag()) || eventType == XmlResourceParser.END_TAG)) {
                    results.put(key, value);
                    key = null;
                    value = null;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Utils.debugLog(TAG, "Exception thrown while parsing resource xml " + resId);
        }
    }

    @SuppressWarnings("PMD.AssignmentInOperand")
    @NonNull
    private static String debugLogcat() {
        StringBuilder sb = new StringBuilder();
        try {
            Process proc = Runtime.getRuntime().exec("logcat -d");
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()));

            String line = "";
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sb.toString();
    }

    @SuppressWarnings("SetTextI18n")
    @NonNull
    private static String debugEnvLocales(@NonNull final Context context) {
        StringBuilder sb = new StringBuilder()
                .append("NATIVE_PAL: \t").append(NATIVE_PAL).append('\n')
                .append("USE_ICU: \t").append(USE_ICU).append("\n\n");

        sb.append("Locale.getDefault(): \t").append(Locale.getDefault()).append('\n')
                .append("LocaleListCompat.getDefault(): \t")
                .append(LocaleListCompat.getDefault()).append('\n');

        sb.append("\nLanguages static: \n")
                .append("systemLocales: \t").append(systemLocales).append('\n')
                .append("lastLocaleList: \t").append(lastLocaleList).append('\n')
                .append("defaultLocale: \t").append(defaultLocale).append('\n')
                .append("locale: \t").append(locale).append('\n');

        for (int i = 0; i < 2; i++) {
            Context c = i == 0 ? context.getApplicationContext() : context;
            sb.append('\n').append(i == 0 ? "Application context" : "This context")
                    .append(": \t").append(c).append('\n')
                    .append("Configuration locales: \t")
                    .append(ConfigurationCompat.getLocales(c.getResources().getConfiguration()))
                    .append('\n');
        }
        return sb.toString();
    }

    @SuppressWarnings({ "SetTextI18n", "NoWhitespaceAfter" })
    @NonNull
    private static String debugAppLocales(@NonNull final Context context) {
        AppLocale[][] collection = prepareAppLocales(context, new int[] { 2, /* 2 */ 0 });
        if (collection[1] == null && appResLocales != null) {
            String[] l = new String[appResLocales.length];
            for (int i = 0; i < appResLocales.length; i++) {
                l[i] = appResLocales[i].locale.toLanguageTag();
            }
            collection[1] = toAppLocales(l);
        }

        int sizeHint = 20 + 5;
        for (int i = 0; i < LOCALE_SCRIPTS.length; i++) {
            sizeHint += ((LOCALE_SCRIPTS[i] == null ? 4 : LOCALE_SCRIPTS[i].length()) + 4) * (i == 0 ? 1 : 2);
        }
        for (int i = 0; i < collection.length; i++) {
            sizeHint += 17 + (collection[i] == null ? 0 : collection[i].length) * (20 + 6 + 11 + 11 + 11 + 5);
        }
        StringBuilder sb = new StringBuilder(sizeHint);
        sb.append("Locale Scripts: " + Build.VERSION.SDK_INT + "=\n").append(LOCALE_SCRIPTS[CACHE]);

        for (int c = 0; c < collection.length; c++) {
            AppLocale[] appLocalesResolved = collection[c];
            if (appLocalesResolved == null) continue;
            String[] localeScripts = new String[2];
            processAppLocales(appLocalesResolved, false, true, localeScripts);

            sb.append("\n\n\n").append(c == 0 ? "Released:" : "Unreleased:")
                    .append("\n").append(localeScripts[RESOLVED]).append("\n\n");

            AppLocale[] appLocalesSubject = c == 0 ? appLocales : appResLocales;
            for (int i = 0, n = appLocalesResolved.length - 1; i <= n; i++) {
                AppLocale appLocale = appLocalesResolved[i];
                Locale sysLocaleCached = i < appLocalesSubject.length
                        ? appLocalesSubject[i].getMatchingSystemLocale() : null;
                sb.append('"').append(appLocale.locale).append('"');
                if (USE_ICU && appLocale instanceof AppLocaleIcu) {
                    sb.append("\t -> ").append(((AppLocaleIcu) appLocale).icuLocale);
                }
                sb.append("\t => ").append(appLocale.sysLocale).append("\t | ")
                        .append(sysLocaleCached).append("\t (")
                        .append(sysLocaleCached.equals(appLocale.sysLocale)).append(")");
                if (i < n) sb.append(",\n");
            }
        }
        return sb.toString();
    }

    private static final String[] DEBUG_ITEMS = new String[] {
            "Locales", "LOCALE_SCRIPTS", "Logcat" };

    @SuppressWarnings({ "SetTextI18n", "NoWhitespaceAfter", "EmptyLineSeparator" })
    public static void debugLangScripts(@NonNull final Context context) {
        final android.widget.TextView textView = new android.widget.TextView(context);

        textView.setTextIsSelectable(true);
        textView.setHorizontallyScrolling(true);
        textView.setSelectAllOnFocus(true);

        String[] debugInfo = new String[DEBUG_ITEMS.length];
        int[] selected = new int[] { -1 };
        Disposable[] disposable = new Disposable[1];

        androidx.appcompat.widget.AppCompatSpinner spinner = new androidx.appcompat.widget.AppCompatSpinner(context);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                context, android.R.layout.simple_spinner_dropdown_item, DEBUG_ITEMS);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> adapterView, View view, int i, long l) {
                if (debugInfo[i] == null) {
                    textView.setText("Working on it...");
                    if (disposable[0] != null) disposable[0].dispose();
                    disposable[0] = Single.fromCallable(() -> {
                        if (i == 0) {
                            return debugEnvLocales(context);
                        } else if (i == 1) {
                            return debugAppLocales(context);
                        } else if (i == 2) {
                            return debugLogcat();
                        }
                        return null;
                    }).subscribeOn(Schedulers.computation())
                            .observeOn(AndroidSchedulers.mainThread())
                            .doOnError(throwable -> {
                                textView.setText(throwable.toString());
                                Log.e(TAG, "Error generating debug info", throwable);
                            })
                            .subscribe(debug -> {
                                debugInfo[i] = debug;
                                textView.setText(debugInfo[i]);
                            });
                } else textView.setText(debugInfo[i]);
                selected[0] = i;
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> adapterView) {
                // nothing
            }
        });

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setView(textView)
                .setCustomTitle(spinner)
                .setNeutralButton("Save", (dialog, i) -> {
                    java.io.File dir = new java.io.File(context.getExternalFilesDir(null), "Debug logs");
                    if (!dir.exists()) dir.mkdir();
                    int index = selected[0];
                    if (index < 0) {
                        Toast.makeText(context, "Content unavailable!", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String filename = "debug_" + (index >= 0 && index < debugInfo.length - 1
                            ? (DEBUG_ITEMS[index]) : System.currentTimeMillis()) + ".log";
                    try {
                        java.io.FileWriter out = new java.io.FileWriter(new java.io.File(dir, filename));
                        out.write(textView.getText().toString());
                        out.close();
                        Toast.makeText(context,
                                "Saved " + filename + " to " + dir, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(context, "Failed to create " + filename + " in "
                                + dir + ": " + e, Toast.LENGTH_LONG).show();
                        e.printStackTrace();
                    }
                })
                .setNegativeButton("Share", (dialog, i) -> {
                    android.content.Intent intent = new android.content.Intent();
                    intent.setAction(android.content.Intent.ACTION_SEND);
                    intent.putExtra(android.content.Intent.EXTRA_TEXT, debugInfo[selected[0]]);
                    intent.setType("text/plain");
                    context.startActivity(android.content.Intent.createChooser(intent, null));
                })
                .setCancelable(true)
                .show();
    }
}
