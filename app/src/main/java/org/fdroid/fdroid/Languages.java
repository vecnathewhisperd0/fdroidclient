package org.fdroid.fdroid;

import android.app.Activity;
import android.app.Application;
import android.app.LocaleConfig;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.icu.util.ULocale;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.ConfigurationCompat;
import androidx.core.os.LocaleListCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class Languages {
    public static final String TAG = "Languages";

    public static final String USE_SYSTEM_DEFAULT = "";

    @ChecksSdkIntAtLeast(api = 33)
    public static final boolean NATIVE_PAL; // PAL = Per app language support

    @ChecksSdkIntAtLeast(api = 24)
    private static final boolean USE_ICU;
    private static LocaleListCompat systemLocales;
    private static LocaleListCompat lastLocaleList;
    private static AppLocale[] appLocales;

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

    public static void ensureLocaleList() {
        LocaleListCompat current = LocaleListCompat.getDefault();
        if (lastLocaleList != null && !current.equals(lastLocaleList)) {
            if (Build.VERSION.SDK_INT >= 24) {
                LocaleList.setDefault((LocaleList) lastLocaleList.unwrap());
            } else {
                Locale.setDefault(lastLocaleList.get(0));
            }
        }
    }

    @SuppressWarnings("EmptyLineSeparator")
    public static void onApplicationCreate(@NonNull final Application app) {
        if (NATIVE_PAL) return;
        // The default locale list seems to be set on creation of an activity (thread):
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/app/ActivityThread.java?q=symbol%3A%5Cbandroid.app.ActivityThread.handleBindApplication%5Cb%20case%3Ayes
        // by calling `LocaleList.setDefault()` with a `LocaleList` passed in from the system
        // which corresponds with the language preference list in system settings (as we desire)
        // and adjusted with the selected per app language at the top on Android 13 onwards
        // with native support.  Unfortunately we'll have to 'mess with' it pre-Android 13
        // so we re-set the default locale list to our modified one at the earliest opportunity.
        // This is particularly important so as to 'propagate' our locale list to the libraries
        // (which then pick up the same by calling `LocaleListCompat.getDefault()`).
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
                ensureLocaleList();
            }
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                // empty
            }
            @Override
            public void onActivityStarted(Activity activity) {
                // empty
            }
            @Override
            public void onActivityResumed(Activity activity) {
                // empty
            }
            @Override
            public void onActivityPaused(Activity activity) {
                // empty
            }
            @Override
            public void onActivityStopped(Activity activity) {
                // empty
            }
            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                // empty
            }
            @Override
            public void onActivityDestroyed(Activity activity) {
                // empty
            }
        });
    }

    private static int compareStringSegments(final String aString, int aPos, int aLen,
                                             String bString, int bPos, int bLen) {
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
        for (int i = 0; i < SCRIPT_HINTS.length; i++) {
            if (c == SCRIPT_HINTS[i]) return true;
        }
        return false;
    }

    public static void updateCacheHint(@NonNull final Context context, final SharedPreferences atStartTime) {
        final String cacheHintKey = "scripts-hint";
        String cached = atStartTime == null ? null : atStartTime.getString(cacheHintKey, null);
        String prefix = Build.VERSION.SDK_INT + "=";
        if (cached != null && cached.startsWith(prefix)) {
            LOCALE_SCRIPTS[CACHE] = cached.substring(prefix.length());
        } else {
            processAppLocales(null, false, true);
            cached = LOCALE_SCRIPTS[CACHE];
            if (atStartTime != null && cached != null && !cached.isEmpty()) {
                atStartTime.edit().putString(cacheHintKey, prefix + cached).apply();
            }
        }
    }

    private static void requireAppLocales(@NonNull final Context activity) {
        if (LOCALE_SCRIPTS[CACHE] == null || LOCALE_SCRIPTS[CACHE].isEmpty()) {
            processAppLocales(null, false, true);
        }

        if (appLocales == null) {
            AppLocale[] list = fetchAppLocales(activity);
            processAppLocales(list, true, true);
            appLocales = list;
        }
    }

    private static AppLocale[] fetchAppLocales(@NonNull final Context activity) {
        AppLocale[] appLocales = null;

        if (Build.VERSION.SDK_INT >= 33) {
            LocaleConfig localeConfig = new LocaleConfig(activity);
            LocaleList locales = localeConfig.getSupportedLocales();
            if (locales != null) {
                appLocales = new AppLocale[locales.size()];
                for (int i = 0; i < locales.size(); i++) {
                    appLocales[i] = createAppLocale(locales.get(i));
                }
            }
        }

        if (appLocales == null) {
            appLocales = toAppLocales(parseAppLocales(activity.getResources()));
        }

        Arrays.sort(appLocales);
        return appLocales;
    }

    private static void processAppLocales(@NonNull AppLocale[] appLocales,
                                          final boolean useCache, final boolean resolve) {
        boolean dryRun = appLocales == null;
        boolean genericHints = dryRun;
        int fallbackOp = SCRIPT_INSIGNIFICANT;
        Locale[] sysLocales = dryRun ? Locale.getAvailableLocales() : null;
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
                            defaultOp[c] = cmp >= 0 ? cmp : SCRIPT_INSIGNIFICANT;
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
                if (sysLocales == null) sysLocales = Locale.getAvailableLocales();

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
        if (sb != null && sb.length() > 0) {
            if (dryRun) LOCALE_SCRIPTS[CACHE] = sb.toString();
            else LOCALE_SCRIPTS[RESOLVED] = sb.toString();
        }
    }

    /**
     * @param activity the {@link AppCompatActivity} this is working as part of
     * @return the singleton to work with
     */
    public static Languages get(@NonNull AppCompatActivity activity) {
        if (singleton == null) {
            singleton = new Languages(activity);
        }
        return singleton;
    }

    public static void updateSystemLocales(Configuration config) {
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
    }

    private static Locale.Builder localeBuilder;

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

    private static AppLocale createAppLocale(@NonNull final Locale locale) {
        return USE_ICU ? new AppLocaleIcu(locale) : new AppLocale(locale);
    }

    private static int compare(final Locale sysLocale, final AppLocale appLocale) {
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

    // Map obsolete language codes to new language codes
    // see https://developer.android.com/reference/java/util/Locale#legacy-language-codes
    @SuppressWarnings("NoWhitespaceAfter")
    private static final String[] OLD_LANG_CODES = { "in", "iw", "ji" };
    @SuppressWarnings("NoWhitespaceAfter")
    private static final String[] NEW_LANG_CODES = { "id", "he", "yi" };

    private static int searchSortedStrings(@NonNull final String[] haystack,
                                           @NonNull final String needle) {
        int i = -1, cmp = -1;
        do {
            cmp = needle.compareTo(haystack[++i]);
        } while (cmp > 0 && i < haystack.length - 1);
        return cmp == 0 ? i : -1;
    }

    private static int searchSortedChars(final char[] haystack, final char needle) {
        int i = -1, cmp = -1;
        do {
            cmp = Character.compare(needle, haystack[++i]);
        } while (cmp > 0 && i < haystack.length - 1);
        return cmp == 0 ? i : -1;
    }

    private static String remapLegacyCode(@NonNull final String lang) {
        int index = searchSortedStrings(OLD_LANG_CODES, lang);
        return index >= 0 ? NEW_LANG_CODES[index] : lang;
    }

    private static String remapLegacyCode(final String lang, final boolean enable) {
        return enable ? remapLegacyCode(lang) : lang;
    }

    private static void setLocaleListDefault(@NonNull final LocaleListCompat newLocales) {
        if (Build.VERSION.SDK_INT >= 24) {
            LocaleList.setDefault((LocaleList) newLocales.unwrap());
        } else {
            Locale.setDefault(locale);
        }
        lastLocaleList = newLocales;
    }

    private static AppLocale matchAppLocale(@NonNull final Context context,
                                            final String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return null;
        Locale l = Locale.forLanguageTag(languageTag);
        String lang = remapLegacyCode(l.getLanguage());
        requireAppLocales(context);
        int i = getStartOfLanguageRange(appLocales, lang, true);
        int j = getEndOfLanguageRange(appLocales, lang, i, true);
        if (i < 0 || j > appLocales.length) return null;
        for (; i < j; i++) {
            AppLocale appLocale = appLocales[i];
            if (l.equals(appLocale.locale) || l.equals(appLocale.getMatchingSystemLocale())) {
                return appLocale;
            }
        }
        return null;
    }

    private static void updateConfiguration(@NonNull final Context context,
                                            @NonNull final LocaleListCompat newLocales) {
        final Resources resources = context.getResources();
        Configuration config = resources.getConfiguration();
        ConfigurationCompat.setLocales(config, newLocales);
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    // AOSP commit fc8c211 "Accept repeated locale as an input of LocaleList construction."
    // https://android.googlesource.com/platform/frameworks/base/+/fc8c211b436aa180818780a6ade107ad30835ef8
    // landed into `LocaleList` in Sep 2020.  Unfortunately the Compat version (`LocaleListCompat`)
    // doesn't cover the edge case for us and simply hands off to platform implementation where available.
    // In other words, we cannot rely on the constructor (to silently drop duplicates) but to do the
    // deduplication on our own (lest IAE will be thrown on older Android versions (presumably <11)).
    private static LocaleListCompat adjustLocaleList(final Locale preferred,
                                                     final LocaleListCompat baseList) {
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

    public static void setLanguage(@NonNull final Context context, final String language) {
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
            //
            // Unfortunately AppCompat 1.7.0-alpha01 introduces a 'nasty' 'bugfix' (I6a94b)
            // https://android-review.googlesource.com/c/platform/frameworks/support/+/2200485
            // which claims to fix `Locale.getDefault()` but breaks `LocaleList.getDefault()`
            // entirely when per-app language is used with `AppCompatDelegate` pre-Android 13
            // so we need to hold off AppCompat 1.7.0 (stay on 1.6.1) until the commit is reverted.
            if (Build.VERSION.SDK_INT >= 31
                    && activity.getResources().getConfiguration().getLayoutDirection() !=
                    TextUtils.getLayoutDirectionFromLocale(locale)) {
                View view = activity.getWindow().getDecorView();
                view.setLayoutDirection(TextUtils.getLayoutDirectionFromLocale(locale));
            }
        }
        // `AppCompatDelegate` would take care to recreate `AppCompatActivity`s as necessary
        /* Intent intent = activity.getIntent();
        if (intent == null) { // when launched as LAUNCHER
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        activity.finish();
        activity.overridePendingTransition(0, 0);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0); */
    }

    /**
     * @return an array of the names of all the supported languages, sorted to
     * match what is returned by {@link Languages#getSupportedLocales()}.
     */
    public static String[] getAllNames(@NonNull final Context context) {
        return mapToArray(context, false, NATIVE_PAL);
    }

    /**
     * @return sorted list of supported locales.
     */
    public static String[] getSupportedLocales(@NonNull final Context context) {
        return mapToArray(context, true, NATIVE_PAL);
    }

    private static String capitalize(@NonNull final String line, final Locale displayLocale) {
        if (displayLocale == null || displayLocale.getLanguage().isEmpty()) {
            return Character.toUpperCase(line.charAt(0)) + line.substring(1);
        }
        return line.substring(0, 1).toUpperCase(displayLocale) + line.substring(1);
    }

    private static String[] mapToArray(@NonNull final Context context, final boolean key,
                                       final boolean matchSystemLocales) {
        String[] names = new String[appLocales.length + 1];
        /* SYSTEM_DEFAULT is a fake one for displaying in a chooser menu. */
        names[0] = key ? USE_SYSTEM_DEFAULT : context.getString(R.string.pref_language_default);
        for (int i = 0; i < appLocales.length; i++) {
            Locale appLocale = matchSystemLocales ? appLocales[i].getMatchingSystemLocale()
                    : appLocales[i].locale;
            names[i + 1] = key ? appLocale.toLanguageTag() : getDisplayName(appLocales[i].locale);
        }
        return names;
    }

    // aligns with AOSP's `LocaleHelper.shouldUseDialectName()`
    // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/com/android/internal/app/LocaleHelper.java?q=symbol%3A%5Cbcom.android.internal.app.LocaleHelper.shouldUseDialectName%5Cb%20case%3Ayes
    @SuppressWarnings("NoWhitespaceAfter")
    private static final String[] DIALECT_LANGS = { "fa", "ro", "zh" };

    private static boolean showDialect(@NonNull final String lang) {
        return USE_ICU && !lang.isEmpty() && searchSortedStrings(DIALECT_LANGS, lang) >= 0;
    }

    @RequiresApi(api = 24)
    private static String getDisplayNameWithDialect(final Locale locale, final Locale displayLocale) {
        return ULocale.forLocale(locale).getDisplayNameWithDialect(ULocale.forLocale(displayLocale));
    }

    private static String getDisplayName(final Locale locale) {
        if (locale == null) return null;
        final String lang = locale.getLanguage();
        final int langLen = lang.length();
        final boolean showDialect = showDialect(lang);
        String name = null;
        LocaleListCompat locales = null;
        int i = -1, n = 0;
        Locale displayLocale = locale;
        while (i <= n && (name == null || name.isEmpty() || (name.startsWith(lang)
                && (name.length() > langLen ? name.charAt(langLen) == ' ' : true)))) {
            if (i == 0 && locales == null) {
                locales = LocaleListCompat.getDefault();
                n = locales.size();
            }
            if (i >= 0) displayLocale = i < n ? locales.get(i) : Locale.ENGLISH;

            name = showDialect ? getDisplayNameWithDialect(locale, displayLocale)
                    : locale.getDisplayName(displayLocale);
            i++;
        }
        return name == null ? null : capitalize(name, displayLocale);
    }

    public static String getCurrentLocale() {
        return getDisplayName(Locale.getDefault());
    }

    public static String getAppLocale() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        return locales.isEmpty() ? USE_SYSTEM_DEFAULT : locales.get(0).toLanguageTag();
    }

    public static Locale[] toLocales(@NonNull final String[] locales) {
        Arrays.sort(locales);
        Locale[] array = new Locale[locales.length];
        for (int i = 0; i < locales.length; i++) {
            array[i] = Locale.forLanguageTag(locales[i]);
        }
        return array;
    }

    public static AppLocale[] toAppLocales(@NonNull final String[] locales) {
        AppLocale[] array = new AppLocale[locales.length];
        for (int i = 0; i < locales.length; i++) {
            array[i] = createAppLocale(Locale.forLanguageTag(locales[i]));
        }
        return array;
    }

    public static String[] parseAppLocales(@NonNull final Resources resources) {
        Set<String> locales = new HashSet<>();
        try (XmlResourceParser parser = resources.getXml(R.xml.locales_config)) {
            int eventType = parser.getEventType();
            while (eventType != XmlResourceParser.END_DOCUMENT) {
                if (eventType == XmlResourceParser.START_TAG
                        && parser.getName().equalsIgnoreCase("locale")) {
                    locales.add(parser.getAttributeValue(0));
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Utils.debugLog(TAG, "Exception thrown while parsing locales_config.xml");
        }
        return locales.toArray(new String[0]);
    }

    @SuppressWarnings("SetTextI18n")
    public static void debugLangScripts(@NonNull final Context context) {
        LOCALE_SCRIPTS[RESOLVED] = null;
        AppLocale[] appLocalesResolved = fetchAppLocales(context);
        processAppLocales(appLocalesResolved, false, true);
        final android.widget.TextView textView = new android.widget.TextView(context);

        StringBuilder sb = new StringBuilder(appLocalesResolved.length * (20 + 6 + 11 + 11 + 11 + 5));
        for (int i = 0, n = appLocalesResolved.length - 1; i <= n; i++) {
            AppLocale appLocale = appLocalesResolved[i];
            Locale sysLocaleCached = appLocales[i].getMatchingSystemLocale();
            sb.append('"').append(appLocale.locale).append('"');
            if (USE_ICU && appLocale instanceof AppLocaleIcu) {
                sb.append("\t -> ").append(((AppLocaleIcu) appLocale).icuLocale);
            }
            sb.append("\t => ").append(appLocale.sysLocale).append("\t | ")
                    .append(sysLocaleCached).append("\t (")
                    .append(sysLocaleCached.equals(appLocale.sysLocale)).append(")");
            if (i < n) sb.append(",\n");
        }
        textView.setText(TextUtils.join(", \n\n", LOCALE_SCRIPTS)
                + "; \n\nLOCALE_SCRIPTS[CACHE] == LOCALE_SCRIPTS[RESOLVED]: "
                + (LOCALE_SCRIPTS[CACHE].equals(LOCALE_SCRIPTS[RESOLVED]))
                + "\n\n\n" + sb.toString());
        textView.setTextIsSelectable(true);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setView(textView)
                .setTitle("LOCALE_SCRIPTS: " + Build.VERSION.SDK_INT + "=")
                .setCancelable(true)
                .show();
    }
}
