package org.fdroid.fdroid;

import android.app.Activity;
import android.app.Application;
import android.app.LocaleConfig;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.icu.util.ULocale;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.text.TextUtils;
import android.view.View;

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

    public static final boolean PER_APP_LANG;

    private static final boolean USE_ICU;
    private static LocaleListCompat systemLocales;
    private static LocaleListCompat lastLocaleList;
    private static AppLocale[] appLocales;

    private static Locale defaultLocale;
    private static Locale locale;
    private static Languages singleton;

    private static final int CACHE = 0, RESOLVED = 1;
    private static final String[] LOCALE_SCRIPTS = new String[2];

    static {
        USE_ICU = Build.VERSION.SDK_INT >= 24;
        PER_APP_LANG = Build.VERSION.SDK_INT >= 33;
        cacheLocaleScriptsHints();
        updateSystemLocales(null);
    }

    private Languages(AppCompatActivity activity) {
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
    public static void onApplicationCreate(final Application app) {
        if (PER_APP_LANG) return;
        // The default locale list seems to be set on creation of an activity (thread):
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/app/ActivityThread.java?q=symbol%3A%5Cbandroid.app.ActivityThread.handleBindApplication%5Cb%20case%3Ayes
        // by calling `LocaleList.setDefault()` with a `LocaleList` passed in from the system
        // which corresponds with the language preference list in system settings (as we desire)
        // and adjusted with the selected per app language at the top on Android 13 onwards
        // with native support.  Unfortunately we'll have to `mess with` it pre-Android 13
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

    // The in-app language picker is designed to interop with the system's locale picker
    // (namely AOSP's `LocalePickerWithRegion`) which prompts the user to select a locale
    // with a country.  Say, the user will normally choose "English (United States)" (en-US)
    // rather than just "English" (en) from the settings UI (even though both locales exist
    // in `Locale.getAvailableLocales()`).  This actually has its advantages, e.g. the user
    // may still benefit from `en-AU` translations if available from the repos (the resolution
    // being handled by the library) with "English (Australia)" as the preferred (in-app) language
    // while the client UI falls back to one of the available English locales (`en-US` or `en-GB`)
    // among the application locales (via `Resources` resolution by the system in native C++ code).
    // But for various reasons this also means we need to 'map' the available application locales
    // (normally with just a language code (occasionally with a country) e.g. `de`, `sr`, `zh-TW`)
    // to a 'best matching' system locale with a country (occasionally with a script)
    // e.g. `de-DE`, `sr-Cyrl-RS`, `zh-Hant-TW` (with a default script and/or country 'imputed' from
    // 'ULocale.addLikelySubtags()' helpfully backed by the system's embedded ICU/CLDR dataset).
    //
    // The most 'correct' way (as `LocalePickerWithRegion` appears to do) would be to start off
    // with `Locale.getAvailableLocales()' and do the matching against the full list (unfortunately
    // to the tune of some eight hundreds).  `CacheHint` leverages the fact that the system locales
    // are (probably loosely) tied with a specific ICU/CLDR version per Android release (API level)
    // https://developer.android.com/guide/topics/resources/internationalization
    // (and vendor customizations to such lengths unlikely) and 'caches' the scripts hint
    // of languages in a bid to 'get by' from resolving from `Locale.getAvailableLocales()'.
    //
    // The whole exercise of 'mapping' application locales to their respective 'best match'
    // system locales might be best illustrated with some examples:
    //
    // CacheHint    implies   App     maximizes to [1]   resolves to  where `getAvailableLocales()'
    //                       locale                      best match   contains
    //  "de-"       SCRIPT_ "de"      "de-Latn-DE"   ->   "de-DE"     "de", "de-BE", "de-DE", ...
    //  "en-" INSIGNIFICANT "en-GB"   "en-Latn-GB"   ->   "en-GB"     "en", "en-GB", "en-US", ...
    //                      "en-US"   "en-Latn-US"   ->   "en-US"
    //  "pt-"               "pt"      "pt-Latn-BR"   ->     "pt" [2]  "pt", "pt-BR", "pt-CH",
    //                      "pt-BR"   "pt-Latn-BR"   ->   "pt-BR"     "pt-MO", "pt-PT", "pt-ST", ...
    //                      "pt-PT"   "pt-Latn-PT"   ->   "pt-PT"
    //  "sr+"       SCRIPT_ "sr"      "sr-Cyrl-RS"   -> "sr-Cyrl-RS"  "sr", "sr-Cyrl", "sr-Cyrl-RS",
    //          SIGNIFICANT                                           "sr-Latn", "sr-Latn-RS", ...
    //  "zh+"               "zh-CN"   "zh-Hans-CN"   -> "zh-Hans-CN"  "zh", "zh-Hans", "zh-Hans-CN",
    //                      "zh-HK"   "zh-Hant-HK"   -> "zh-Hant-HK"  "zh-Hant", "zh-Hant-HK",
    //                      "zh-TW"   "zh-Hant-TW"   -> "zh-Hant-TW"  "zh-Hant-TW", ...
    //  "sc!"   NOT_PRESENT "sc"      "sc-Latn-IT"   ->   "sc-IT" [3]  nil
    //
    // [1] via 'ULocale.addLikelySubtags()'
    // [2] which would otherwise resolve to best match "pt-BR" but for the country "BR" 'discounted'
    //     (since the user would have otherwise chosen "pt-BR" out of the three options available);
    //     falls back to 'vanilla' `pt` since there is no other clue what country to impute
    // [3] assumes SCRIPT_INSIGNIFICANT; e.g. Sardinian (sc) only seems
    //     to be added to the language picker in Android Settings in Dec 2022
    //     https://android.googlesource.com/platform/frameworks/base/+/fa276959d68a7fc3464816a8cdd36f2f498be530
    //
    private static class CacheHint {
        private static final int EXACT = 0, HIGHER = 1, LOWER = 2;
        private String[][] biases = new String[3][];
        private int[] keys = new int[3];
        private String[][] commons;
        private int flags;

        private static final int MATCH_EXACT = 1 << EXACT;
        private static final int MATCH_HIGHER = 1 << HIGHER;
        private static final int MATCH_LOWER = 1 << LOWER;
        private static final int DESCENDING = 1 << 3;
        private static final int DONE = 1 << 4;

        public static final String SLOT = "*", SLUGS = "&";

        CacheHint(final int target, final boolean descending) {
            keys[EXACT] = target;
            if (descending) flags |= DESCENDING;
        }

        public CacheHint setCommonGround(final String... value) {
            if (commons == null) commons = new String[1][];
            commons[0] = value;
            return this;
        }

        public CacheHint putCacheHint(final int key, final String... value) {
            return putCacheHint(key, key, value);
        }

        public CacheHint putCacheHint(final int from, final int to, final String... value) {
            if ((flags & DONE) == 0) {
                int target = keys[EXACT];
                int bias = from <= target && target <= to ? EXACT
                        : (from > target ? HIGHER : LOWER);
                biases[bias] = value;
                flags |= 1 << bias;
                if ((flags & MATCH_EXACT) != 0 || isMarked(flags, MATCH_HIGHER | MATCH_LOWER)
                        || (flags & ((flags & DESCENDING) != 0 ? MATCH_LOWER : MATCH_HIGHER)) != 0) {
                    flags |= DONE;
                }
            }
            return this;
        }

        public String commit() {
            return commit(true, true);
        }

        public String commit(final boolean takeLower, final boolean orEither) {
            int bias = 0;
            if ((flags & MATCH_EXACT) == 0) {
                if (takeLower && (flags & MATCH_LOWER) != 0) {
                    bias = LOWER;
                } else if (!takeLower && (flags & MATCH_HIGHER) != 0) {
                    bias = HIGHER;
                } else if (orEither) {
                    bias = takeLower ? HIGHER : LOWER;
                }
            }
            if ((flags & (1 << bias)) == 0) return null;
            return compose(bias);
        }

        private int sizeHint(final int common, final int bias) {
            int size = 0;
            for (int c = 0; c < 2; c++) {
                int entry = c == 0 ? common : bias;
                String[][] array = c == 0 ? commons : biases;
                if (entry >= 0 && entry < array.length) {
                    String[] parts = array[entry];
                    for (int i = 0; i < parts.length; i++) {
                        String part = parts[i];
                        if (part != SLOT && part != SLUGS) size += part.length();
                    }
                }
            }
            return size;
        }

        private String compose(final int bias) {
            String[] value = biases[bias];
            if (value == null || value.length < 1) return null;
            boolean delta = value[0] == SLUGS;
            if (!delta) return value.length == 1 ? value[0] : TextUtils.join("", value);
            String[] base = commons[0];
            if (base == null) return null;
            StringBuilder sb = new StringBuilder(sizeHint(0, bias));
            int p = 1;
            for (int i = 0; i < base.length; i++) {
                String s = base[i];
                sb.append(s == SLOT && p < value.length ? value[p++] : s);
            }
            return sb.toString();
        }
    }

    @SuppressWarnings("LineLength")
    // Changed: ast                                                                 hi                                                             sc                                     yue
    //   Hand-crafted based on https://github.com/localazy/android-locales
    //    Android 6 (API 23, Marshmallow):  he=>iw, id=>in
    // 23=af-ar-ast!be-bg-bn-ca-cs-cy-da-de-el-en-eo-es-et-eu-fa-fi-fil-fr-gd-gl-he-hi-hr-hu-id-is-it-ja-kn-ko-lt-lv-ml-mn-nb-nl-nn-pa+pl-pt-ro-ru-sc!sk-sl-sq-sr+sv-sw-ta-te-th-tr-uk-vi-yue!zh+
    //    Android 7.0 (API 24, Nougat):     he=>iw, id=>in;   Delta=0
    // 24=af-ar-ast!be-bg-bn-ca-cs-cy-da-de-el-en-eo-es-et-eu-fa-fi-fil-fr-gd-gl-he-hi-hr-hu-id-is-it-ja-kn-ko-lt-lv-ml-mn-nb-nl-nn-pa+pl-pt-ro-ru-sc!sk-sl-sq-sr+sv-sw-ta-te-th-tr-uk-vi-yue!zh+
    //    Android 7.1 (API 25, Nougat):     he=>iw, id=>in;   Delta=0
    // 25=af-ar-ast!be-bg-bn-ca-cs-cy-da-de-el-en-eo-es-et-eu-fa-fi-fil-fr-gd-gl-he-hi-hr-hu-id-is-it-ja-kn-ko-lt-lv-ml-mn-nb-nl-nn-pa+pl-pt-ro-ru-sc!sk-sl-sq-sr+sv-sw-ta-te-th-tr-uk-vi-yue!zh+
    //    Android 8.0 (API 26, Oreo):       he=>iw, id=>in;   Delta=ast!->ast-, yue!->yue-
    // 26=af-ar-ast-be-bg-bn-ca-cs-cy-da-de-el-en-eo-es-et-eu-fa-fi-fil-fr-gd-gl-he-hi-hr-hu-id-is-it-ja-kn-ko-lt-lv-ml-mn-nb-nl-nn-pa+pl-pt-ro-ru-sc!sk-sl-sq-sr+sv-sw-ta-te-th-tr-uk-vi-yue-zh+
    //    Android 8.1 (API 27, Oreo):       he=>iw, id=>in;   Delta=0
    // 27=af-ar-ast-be-bg-bn-ca-cs-cy-da-de-el-en-eo-es-et-eu-fa-fi-fil-fr-gd-gl-he-hi-hr-hu-id-is-it-ja-kn-ko-lt-lv-ml-mn-nb-nl-nn-pa+pl-pt-ro-ru-sc!sk-sl-sq-sr+sv-sw-ta-te-th-tr-uk-vi-yue-zh+
    //    Android 9 (API 28, Pie):          he=>iw, id=>in;   Delta=yue-->yue+
    // 28=af-ar-ast-be-bg-bn-ca-cs-cy-da-de-el-en-eo-es-et-eu-fa-fi-fil-fr-gd-gl-he-hi-hr-hu-id-is-it-ja-kn-ko-lt-lv-ml-mn-nb-nl-nn-pa+pl-pt-ro-ru-sc!sk-sl-sq-sr+sv-sw-ta-te-th-tr-uk-vi-yue+zh+
    //    Android 10 (API 29):              he=>iw, id=>in;   Delta=0
    // 29=af-ar-ast-be-bg-bn-ca-cs-cy-da-de-el-en-eo-es-et-eu-fa-fi-fil-fr-gd-gl-he-hi-hr-hu-id-is-it-ja-kn-ko-lt-lv-ml-mn-nb-nl-nn-pa+pl-pt-ro-ru-sc!sk-sl-sq-sr+sv-sw-ta-te-th-tr-uk-vi-yue+zh+
    //
    //   Obtained on a real device
    //    Android 8.0, Samsung (tallies with above)
    // 26=af-ar-ast-be-bg-bn-ca-cs-cy-da-de-el-en-eo-es-et-eu-fa-fi-fil-fr-gd-gl-he-hi-hr-hu-id-is-it-ja-kn-ko-lt-lv-ml-mn-nb-nl-nn-pa+pl-pt-ro-ru-sc!sk-sl-sq-sr+sv-sw-ta-te-th-tr-uk-vi-yue-zh+
    //    Android 11 (API 30): tbd
    //    Android 12 (API 31), Samsung                        Delta=0 (against API 29)
    // 31=af-ar-ast-be-bg-bn-ca-cs-cy-da-de-el-en-eo-es-et-eu-fa-fi-fil-fr-gd-gl-he-hi-hr-hu-id-is-it-ja-kn-ko-lt-lv-ml-mn-nb-nl-nn-pa+pl-pt-ro-ru-sc!sk-sl-sq-sr+sv-sw-ta-te-th-tr-uk-vi-yue+zh+
    //    Android 13 (API 33), Asus                           Delta=sc!->sc-
    // 33=af-ar-ast-be-bg-bn-ca-cs-cy-da-de-el-en-eo-es-et-eu-fa-fi-fil-fr-gd-gl-he-hi-hr-hu-id-is-it-ja-kn-ko-lt-lv-ml-mn-nb-nl-nn-pa+pl-pt-ro-ru-sc-sk-sl-sq-sr+sv-sw-ta-te-th-tr-uk-vi-yue+zh+
    //    Android 14 (API 34), Samsung                        Delta=hi-->hi+
    // 34=af-ar-ast-be-bg-bn-ca-cs-cy-da-de-el-en-eo-es-et-eu-fa-fi-fil-fr-gd-gl-he-hi+hr-hu-id-is-it-ja-kn-ko-lt-lv-ml-mn-nb-nl-nn-pa+pl-pt-ro-ru-sc-sk-sl-sq-sr+sv-sw-ta-te-th-tr-uk-vi-yue+zh+
    //
    private static void cacheLocaleScriptsHints() {
        LOCALE_SCRIPTS[CACHE] = new CacheHint(Build.VERSION.SDK_INT, true)
                .setCommonGround("af-ar-",
                        /* ast */ CacheHint.SLOT,
                        "be-bg-bn-ca-cs-cy-da-de-el-en-eo-es-et-eu-fa-fi-fil-fr-gd-gl-he-",
                        /* hi */ CacheHint.SLOT,
                        "hr-hu-id-is-it-ja-kn-ko-lt-lv-ml-mn-nb-nl-nn-pa+pl-pt-ro-ru-",
                        /* sc */ CacheHint.SLOT,
                        "sk-sl-sq-sr+sv-sw-ta-te-th-tr-uk-vi-",
                        /* yue */ CacheHint.SLOT,
                        "zh+")
                .putCacheHint(34,     CacheHint.SLUGS, "ast-", "hi+", "sc-", "yue+")
                .putCacheHint(33,     CacheHint.SLUGS, "ast-", "hi-", "sc-", "yue+")
                .putCacheHint(31,     CacheHint.SLUGS, "ast-", "hi-", "sc!", "yue+")
                .putCacheHint(28, 29, CacheHint.SLUGS, "ast-", "hi-", "sc!", "yue+")
                .putCacheHint(26, 27, CacheHint.SLUGS, "ast-", "hi-", "sc!", "yue-")
                .putCacheHint(23, 25, CacheHint.SLUGS, "ast!", "hi-", "sc!", "yue!")
                .commit();
    }

    private static int getStartOfLanguageRange(final AppLocale[] appLocales, final String appLang) {
        int i = 0;
        String lang = appLocales[i].getLanguage();
        int compare = appLang.compareToIgnoreCase(lang);
        while (compare > 0 && i < appLocales.length - 1) {
            lang = appLocales[++i].getLanguage();
            compare = appLang.compareToIgnoreCase(lang);
        }
        if (compare != 0) return -1;
        return i;
    }

    private static int getEndOfLanguageRange(final AppLocale[] appLocales, final String appLang,
                                             final int i) {
        int j = i + 1;
        for (; j < appLocales.length; j++) {
            if (!appLang.equalsIgnoreCase(appLocales[j].getLanguage())) break;
        }
        return j;
    }

    @SuppressWarnings("NoWhitespaceAfter")
    private static final char[] SCRIPT_HINTS = new char[] { '+', '-', '!' };
    private static final int SCRIPT_SIGNIFICANT = 0, SCRIPT_INSIGNIFICANT = 1, NOT_PRESENT = 2;

    private static boolean isStopChar(final char c) {
        return isStopChar(c, false);
    }

    private static boolean isStopChar(final char c, final boolean forMatching) {
        for (int i = 0; i < SCRIPT_HINTS.length; i++) {
            if (c == SCRIPT_HINTS[i]) return true;
        }
        if (!forMatching && (c == CacheHint.SLOT.charAt(CacheHint.SLOT.length() - 1)
                || c == CacheHint.SLUGS.charAt(CacheHint.SLUGS.length() - 1))) {
            return true;
        }
        return false;
    }

    private static void requireAppLocales(final Context activity) {
        if (appLocales != null) return;

        appLocales = computeAppLocales(activity, true, true);
    }

    private static AppLocale[] computeAppLocales(final Context activity,
                                                 final boolean useCache, final boolean resolve) {
        AppLocale[] appLocales = null;
        Locale[] sysLocales = null;

        if (Build.VERSION.SDK_INT >= 33) {
            LocaleConfig localeConfig = new LocaleConfig(activity);
            LocaleList locales = localeConfig.getSupportedLocales();
            if (locales != null) {
                appLocales = new AppLocale[locales.size()];
                for (int i = 0; i < locales.size(); i++) {
                    appLocales[i] = new AppLocale(locales.get(i));
                }
            }
        }

        if (appLocales == null) {
            appLocales = toAppLocales(parseAppLocales(activity.getResources()));
        }

        Arrays.sort(appLocales);

        StringBuilder sb = null;
        int i = 0;
        int j = 0;
        int k = 0;
        String lang = "";
        String script = "";
        int langLocales = 0;
        int langScripts = 0;
        for (; i < appLocales.length; i++) {
            String appLang = remapLegacyCode(appLocales[j].getLanguage());
            j = getEndOfLanguageRange(appLocales, appLang, i);
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
                int[] cachePos = new int[LOCALE_SCRIPTS.length];
                for (int c = 0; c < LOCALE_SCRIPTS.length; c++) {
                    String cache = LOCALE_SCRIPTS[c];
                    if (cache == null || cache.isEmpty()) continue;
                    int pos = cache.indexOf(appLang, cachePos[c]);
                    int len = appLang.length();
                    if (pos != -1 && cache.length() > pos + len
                            && (pos == cachePos[c] || isStopChar(cache.charAt(pos - 1)))) {
                        char op = cache.charAt(pos + len);
                        if (isStopChar(op, true)) {
                            for (int l = i; l < j; l++) {
                                AppLocale appLocale = appLocales[l];
                                appLocale.flags |= MATCHSYS_CACHED;
                                if (op == SCRIPT_HINTS[SCRIPT_SIGNIFICANT]) {
                                    appLocale.flags |= MATCHSYS_SCRIPT;
                                }
                            }
                            cachePos[c] = pos + len + 1;
                            handled = true;
                            break;
                        }
                    }
                }
                if (handled) {
                    i = j - 1;
                    continue;
                }
            }

            if (!resolve) continue;
            if (sb == null) sb = new StringBuilder(4 * appLocales.length);
            if (sysLocales == null) sysLocales = Locale.getAvailableLocales();

            String sysLang = remapLegacyCode(sysLocales[k].getLanguage());
            int compare = appLang.compareToIgnoreCase(sysLang);
            while (compare > 0 && k < sysLocales.length - 1) {
                sysLang = remapLegacyCode(sysLocales[++k].getLanguage());
                compare = appLang.compareToIgnoreCase(sysLang);
            }
            if (compare == 0) {
                for (; k < sysLocales.length; k++) {
                    Locale sysLocale = sysLocales[k];
                    if (!appLang.equalsIgnoreCase(remapLegacyCode(sysLocale.getLanguage()))) break;
                    if (!sysLocale.getVariant().isEmpty()) continue;
                    langLocales++;
                    int sysParts = getParts(sysLocale);
                    String sysScript = sysLocale.getScript();
                    if (!sysScript.isEmpty() && !script.equalsIgnoreCase(sysScript)) {
                        langScripts++;
                        script = sysScript;
                    }
                    String sysCountry = sysLocale.getCountry();
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
                }
                if (!appLang.isEmpty()) {
                    sb.append(appLang);
                    sb.append(SCRIPT_HINTS[langScripts > 0
                            ? SCRIPT_SIGNIFICANT : SCRIPT_INSIGNIFICANT]);
                }
                lang = "";
                script = "";
                langLocales = 0;
                langScripts = 0;
            } else {
                if (!appLang.isEmpty()) {
                    sb.append(appLang);
                    sb.append(SCRIPT_HINTS[NOT_PRESENT]);
                }
            }
            i = j - 1;
        }
        if (sb != null && sb.length() > 0) {
            LOCALE_SCRIPTS[RESOLVED] = sb.toString();
        }

        return appLocales;
    }

    /**
     * @param activity the {@link AppCompatActivity} this is working as part of
     * @return the singleton to work with
     */
    public static Languages get(AppCompatActivity activity) {
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
            systemLocales = Build.VERSION.SDK_INT >= 24
                    ? LocaleListCompat.wrap(LocaleList.getDefault())
                    : LocaleListCompat.create(Locale.getDefault());
        }
        locale = null;
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
    private static final int DISCOUNT_LANG = LANG << DISCOUNT_OFFSET;
    private static final int DISCOUNT_SCRIPT = SCRIPT << DISCOUNT_OFFSET;
    private static final int DISCOUNT_COUNTRY = COUNTRY << DISCOUNT_OFFSET;

    private static final int MATCHSYS_OFFSET = 16;
    private static final int MATCHSYS_CACHED = 1 << MATCHSYS_OFFSET;
    private static final int MATCHSYS_SCRIPT = SCRIPT << MATCHSYS_OFFSET;
    private static final int MATCHSYS_COUNTRY = COUNTRY << MATCHSYS_OFFSET;

    private static final int SYSPRESENT_OFFSET = 20;
    private static final int SYSPRESENT_EXACT = 1 << SYSPRESENT_OFFSET;
    private static final int SYSPRESENT_SCRIPT = SCRIPT << SYSPRESENT_OFFSET;

    private static class AppLocale implements Comparable<AppLocale> {
        Locale locale;
        ULocale icuLocale;
        Locale sysLocale = null;
        int flags = 0;

        AppLocale(final Locale locale) {
            this.locale = locale;
            if (USE_ICU) {
                this.icuLocale = ULocale.addLikelySubtags(ULocale.forLocale(locale));
            } else {
                this.icuLocale = null;
            }
            setParts();
        }

        private void setParts() {
            String l = null, s = null, c = null;
            if (locale != null) {
                l = locale.getLanguage();
                s = locale.getScript();
                c = locale.getCountry();
                if (!l.isEmpty()) flags |= LANG;
                if (!s.isEmpty()) flags |= SCRIPT;
                if (!c.isEmpty()) flags |= COUNTRY;
            }
            if (icuLocale != null) {
                String uL = icuLocale.getLanguage();
                String uS = icuLocale.getScript();
                String uC = icuLocale.getCountry();
                if (!uL.isEmpty()) {
                    flags |= U_LANG;
                    if (!uL.equalsIgnoreCase(l)) flags |= IMPUTED_LANG;
                }
                if (!uS.isEmpty()) {
                    flags |= U_SCRIPT;
                    if (!uS.equalsIgnoreCase(s)) flags |= IMPUTED_SCRIPT;
                }
                if (!uC.isEmpty()) {
                    flags |= U_COUNTRY;
                    if (!uC.equalsIgnoreCase(c)) flags |= IMPUTED_COUNTRY;
                }
            }
        }

        public String getLanguage() {
            return locale.getLanguage();
        }

        public String getScript() {
            return getScript(false);
        }

        public String getScript(final boolean impute) {
            String script = locale.getScript();
            return (script.isEmpty() && impute && icuLocale != null) ? icuLocale.getScript() : script;
        }

        public String getCountry() {
            return getCountry(false);
        }

        public String getCountry(final boolean impute) {
            String country = locale.getCountry();
            return (country.isEmpty() && impute && icuLocale != null) ? icuLocale.getCountry() : country;
        }

        public Locale getMatchingSystemLocale() {
            if (sysLocale != null) {
                return sysLocale;
            }
            if (icuLocale != null && (flags & MATCHSYS_CACHED) != 0) {
                Locale.Builder builder = getBuilder().setLanguage(icuLocale.getLanguage());
                if ((flags & DISCOUNT_SCRIPT) == 0 && ((flags & MATCHSYS_SCRIPT) !=
                        0 || isMarked(flags, SCRIPT | IMPUTED_SCRIPT))) {
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

        @Override
        public String toString() {
            return locale.toLanguageTag();
        }

        @Override
        public int compareTo(AppLocale another) {
            int compare = 0;
            String a = "";
            String b = "";
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

    private static Locale.Builder localeBuilder;

    private static Locale.Builder getBuilder() {
        if (localeBuilder == null) {
            localeBuilder = new Locale.Builder();
        } else {
            localeBuilder.clear();
        }
        return localeBuilder;
    }

    private static int getParts(final Locale locale) {
        int flags = 0;
        if (!locale.getLanguage().isEmpty()) flags |= LANG;
        if (!locale.getScript().isEmpty()) flags |= SCRIPT;
        if (!locale.getCountry().isEmpty()) flags |= COUNTRY;
        return flags;
    }

    private static int compare(final Locale sysLocale, final AppLocale appLocale) {
        int flags = 0;
        String l = remapLegacyCode(sysLocale.getLanguage());
        String s = sysLocale.getScript();
        String c = sysLocale.getCountry();
        if (l.equalsIgnoreCase(remapLegacyCode(appLocale.getLanguage()))) flags |= LANG;
        if (s.equalsIgnoreCase(appLocale.getScript())) flags |= SCRIPT;
        if (c.equalsIgnoreCase(appLocale.getCountry())) flags |= COUNTRY;
        if (appLocale.icuLocale != null) {
            if ((appLocale.flags & IMPUTED_LANG) != 0
                    && l.equalsIgnoreCase(remapLegacyCode(appLocale.icuLocale.getLanguage()))) {
                flags |= U_LANG;
            }
            if ((appLocale.flags & IMPUTED_SCRIPT) != 0
                    && s.equalsIgnoreCase(appLocale.icuLocale.getScript())) flags |= U_SCRIPT;
            if ((appLocale.flags & IMPUTED_COUNTRY) != 0
                    && c.equalsIgnoreCase(appLocale.icuLocale.getCountry())) flags |= U_COUNTRY;
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

    private static String remapLegacyCode(final String lang) {
        // Map obsolete language codes to new language codes
        // see https://developer.android.com/reference/java/util/Locale#legacy-language-codes
        if ("iw".equals(lang)) {
            return "he";
        } else if ("in".equals(lang)) {
            return "id";
        } else if ("ji".equals(lang)) {
            return "yi";
        }
        return lang;
    }

    private static void setLocaleListDefault(final LocaleListCompat newLocales) {
        if (Build.VERSION.SDK_INT >= 24) {
            LocaleList.setDefault((LocaleList) newLocales.unwrap());
        } else {
            Locale.setDefault(locale);
        }
        lastLocaleList = newLocales;
    }

    private static AppLocale matchAppLocale(final Context context, final String language) {
        if (language == null || language.isEmpty()) return null;
        Locale l = Locale.forLanguageTag(language);
        requireAppLocales(context);
        int i = getStartOfLanguageRange(appLocales, l.getLanguage());
        int j = getEndOfLanguageRange(appLocales, l.getLanguage(), i);
        if (i < 0 || j > appLocales.length) return null;
        for (; i < j; i++) {
            AppLocale appLocale = appLocales[i];
            if (l.equals(appLocale.locale)
                    || l.equals(appLocale.getMatchingSystemLocale())) {
                return appLocale;
            }
        }
        return null;
    }

    /**
     * Handles setting the language if it is different than the current language,
     * or different than the current system-wide locale.
     */
    public static void setLanguage(final Context context) {
        if (!Preferences.get().isLanguageSet()) {
            locale = defaultLocale;
            return;
        }
        String language = Preferences.get().getLanguage();
        setLanguage(context, language);
    }

    public static void setLanguage(final Context context, final String language) {
        boolean changed = locale == null;
        if (language == null || language.equals(USE_SYSTEM_DEFAULT)) {
            if (Build.VERSION.SDK_INT >= 33 && locale == null) {
                return;
            }
            changed = locale != null;
            locale = defaultLocale;
        } else {
            Locale sysLocale;
            AppLocale appLocale = matchAppLocale(context, language);
            if (appLocale != null) {
                sysLocale = appLocale.getMatchingSystemLocale();
            } else {
                sysLocale = Locale.forLanguageTag(language);
            }
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

            if (language.equals(USE_SYSTEM_DEFAULT)) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(locale));
            }

            if (PER_APP_LANG) {
                Preferences.get().clearLanguage();
            } else {
                setLocaleListDefault(newLocales);
            }
        }
    }

    private static void updateConfiguration(final Context context,
                                            final LocaleListCompat newLocales) {
        final Resources resources = context.getResources();
        Configuration config = resources.getConfiguration();
        if (Build.VERSION.SDK_INT >= 16) {
            ConfigurationCompat.setLocales(config, newLocales);
        } else {
            config.setLocale(locale);
        }
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    // AOSP commit fc8c211 "Accept repeated locale as an input of LocaleList construction."
    // https://android.googlesource.com/platform/frameworks/base/+/fc8c211b436aa180818780a6ade107ad30835ef8
    // landed into `LocaleList` in Sep 2020.  Unfortunately the Compat version (`LocaleListCompat`)
    // doesn't cover the edge case for us and simply hands off to platform implementation where available.
    // In other words, we cannot rely on the constructor but to do the deduplication on our own
    // (lest IAE will be thrown on older Android versions (presumably pre-Android 11)).
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
     * Force reload the {@link AppCompatActivity} to make language changes take effect.
     *
     * @param activity the {@code AppCompatActivity} to force reload
     */
    public static void forceChangeLanguage(AppCompatActivity activity) {
        if (!PER_APP_LANG) {
            // Cherry-picked from AOSP commit 9752b73 included in AppCompat 1.7.0-alpha02:
            // https://android.googlesource.com/platform/frameworks/support/+/9752b7383244c2ab548970d89a257ef368183b88
            // `To workaround the android framework issue(b/242026447) which doesn't update the
            // layout direction after recreating in Android S.` (adapted with modification).
            //
            // Unfortunately AppCompat 1.7.0-alpha01 introduces a 'nasty' 'bugfix' (I6a94b)
            // https://android-review.googlesource.com/c/platform/frameworks/support/+/2200485
            // which claims to fix `Locale.getDefault()` but breaks `LocaleList.getDefault()`
            // entirely when per-app language is used with AppCompatDelegate pre-Android 13
            // so we need to hold off AppCompat 1.7.0 (stay on 1.6.1) until the same is reverted.
            if (Build.VERSION.SDK_INT >= 31
                    && activity.getResources().getConfiguration().getLayoutDirection() !=
                    TextUtils.getLayoutDirectionFromLocale(locale)) {
                View view = activity.getWindow().getDecorView();
                view.setLayoutDirection(TextUtils.getLayoutDirectionFromLocale(locale));
            }
        }
        // `AppCompatDelegate` would take care to recreate `Activity` as necessary
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
    public String[] getAllNames() {
        return mapToArray(context, false, PER_APP_LANG);
    }

    /**
     * @return sorted list of supported locales.
     */
    public String[] getSupportedLocales() {
        return mapToArray(context, true, PER_APP_LANG);
    }

    private static String capitalize(final String line, final Locale displayLocale) {
        if (displayLocale == null || displayLocale.getLanguage().isEmpty()) {
            return Character.toUpperCase(line.charAt(0)) + line.substring(1);
        }
        return line.substring(0, 1).toUpperCase(displayLocale) + line.substring(1);
    }

    private String[] mapToArray(final Context context, final boolean key,
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

    private static boolean showDialect(final String lang) {
        if (!USE_ICU || lang == null || lang.isEmpty()) return false;
        // aligns with AOSP's internal `LocaleHelper.shouldUseDialectName()`
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/com/android/internal/app/LocaleHelper.java?q=symbol%3A%5Cbcom.android.internal.app.LocaleHelper.shouldUseDialectName%5Cb%20case%3Ayes
        if ("fa".equals(lang)) {
            return true;
        } else if ("ro".equals(lang)) {
            return true;
        } else if ("zh".equals(lang)) {
            return true;
        }
        return false;
    }

    private static String getDisplayName(final Locale locale) {
        if (locale == null) return null;
        final String lang = locale.getLanguage();
        final boolean showDialect = showDialect(lang);
        String name = null;
        LocaleListCompat locales = null;
        int i = -1, n = 0;
        Locale displayLocale = locale;
        while (i <= n && (name == null || name.isEmpty() || name.equals(lang))) {
            if (i == 0 && locales == null) {
                locales = LocaleListCompat.getDefault();
                n = locales.size();
            }
            if (i >= 0) displayLocale = i < n ? locales.get(i) : Locale.ENGLISH;

            if (showDialect) {
                ULocale icuLocale = ULocale.forLocale(displayLocale);
                name = icuLocale.getDisplayNameWithDialect(icuLocale);
            } else {
                name = locale.getDisplayName(displayLocale);
            }
            i++;
        }
        if (name == null) return null;
        return capitalize(name, displayLocale);
    }

    public static String getCurrentLocale() {
        return getDisplayName(Locale.getDefault());
    }

    public static String getAppLocale() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (!locales.isEmpty()) {
            return locales.get(0).toLanguageTag();
        }
        return "";
    }

    public static Locale[] toLocales(final String[] locales) {
        Arrays.sort(locales);
        Locale[] array = new Locale[locales.length];
        for (int i = 0; i < locales.length; i++) {
            array[i] = Locale.forLanguageTag(locales[i]);
        }
        return array;
    }

    public static AppLocale[] toAppLocales(final String[] locales) {
        AppLocale[] array = new AppLocale[locales.length];
        for (int i = 0; i < locales.length; i++) {
            array[i] = new AppLocale(Locale.forLanguageTag(locales[i]));
        }
        return array;
    }

    public static String[] parseAppLocales(final Resources resources) {
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

    public static void debugLangScripts(final Context context) {
        LOCALE_SCRIPTS[RESOLVED] = null;
        computeAppLocales(context, false, true);
        final android.widget.TextView textView = new android.widget.TextView(context);
        textView.setText(TextUtils.join(", \n\n", LOCALE_SCRIPTS)
                + "; \n\nLOCALE_SCRIPTS[CACHE] == LOCALE_SCRIPTS[RESOLVED]: "
                + (LOCALE_SCRIPTS[CACHE].equals(LOCALE_SCRIPTS[RESOLVED])));
        textView.setTextIsSelectable(true);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setView(textView)
                .setTitle("LOCALE_SCRIPTS: " + Build.VERSION.SDK_INT + "=")
                .setCancelable(true)
                .show();
    }
}
