/*
 * Copyright (C) 2019 Michael Pöhn
 * Copyright (C) 2014-2018 Hans-Christoph Steiner
 * Copyright (C) 2014-2017 Peter Serwylo
 * Copyright (C) 2015-2016 Daniel Martí
 * Copyright (C) 2015 Dominik Schürmann
 * Copyright (C) 2018 Torsten Grote
 * Copyright (C) 2018 dkanada
 * Copyright (C) 2018 Senecto Limited
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
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */

package org.fdroid.fdroid.views;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.util.ObjectsCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDivider;

import org.fdroid.fdroid.BuildConfig;
import org.fdroid.fdroid.AppUpdateStatusManager;
import org.fdroid.fdroid.FDroidApp;
import org.fdroid.fdroid.Languages;
import org.fdroid.fdroid.Preferences;
import org.fdroid.fdroid.R;
import org.fdroid.fdroid.RepoUpdateManager;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.installer.InstallHistoryService;
import org.fdroid.fdroid.installer.PrivilegedInstaller;
import org.fdroid.fdroid.installer.SessionInstallManager;
import org.fdroid.fdroid.views.appdetails.AntiFeaturesListingView;
import org.fdroid.fdroid.work.CleanCacheWorker;
import org.fdroid.fdroid.work.FDroidMetricsWorker;
import org.fdroid.fdroid.work.RepoUpdateWorker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import info.guardianproject.netcipher.proxy.OrbotHelper;

public class PreferencesFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String TAG = "PreferencesFragment";

    private static final String[] SUMMARIES_TO_UPDATE = {
            Preferences.PREF_OVER_WIFI,
            Preferences.PREF_OVER_DATA,
            Preferences.PREF_UPDATE_INTERVAL,
            Preferences.PREF_UPDATE_NOTIFICATION_ENABLED,
            Preferences.PREF_SHOW_ANTI_FEATURES,
            Preferences.PREF_SHOW_INCOMPAT_VERSIONS,
            Preferences.PREF_THEME,
            Preferences.PREF_USE_PURE_BLACK_DARK_THEME,
            Preferences.PREF_FORCE_TOUCH_APPS,
            Preferences.PREF_LOCAL_REPO_NAME,
            Preferences.PREF_LANGUAGE,
            Preferences.PREF_KEEP_CACHE_TIME,
            Preferences.PREF_EXPERT,
            Preferences.PREF_PRIVILEGED_INSTALLER,
            Preferences.PREF_ENABLE_PROXY,
            Preferences.PREF_PROXY_HOST,
            Preferences.PREF_PROXY_PORT,
    };

    private static final int[] UPDATE_INTERVAL_NAMES = {
            R.string.interval_never,
            R.string.interval_2w,
            R.string.interval_1w,
            R.string.interval_1d,
            R.string.interval_12h,
            R.string.interval_4h,
            R.string.interval_1h,
    };

    private static final int REQUEST_INSTALL_ORBOT = 0x1234;

    private PreferenceGroup otherPrefGroup;
    private LiveSeekBarPreference overWifiSeekBar;
    private LiveSeekBarPreference overDataSeekBar;
    private LiveSeekBarPreference updateIntervalSeekBar;
    private SwitchPreferenceCompat enableProxyCheckPref;
    private SwitchPreferenceCompat useDnsCacheCheckPref;
    private SwitchPreferenceCompat useTorCheckPref;
    private Preference updateAutoDownloadPref;
    private SwitchPreferenceCompat keepInstallHistoryPref;
    private SwitchPreferenceCompat sendToFDroidMetricsPref;
    private Preference installHistoryPref;
    private long currentKeepCacheTime;

    private LinearSmoothScroller topScroller;

    private RequestManager glideRequestManager;
    private Preference ipfsGateways;
    private RepoUpdateManager repoUpdateManager;
    private long nextUpdateCheck = Long.MAX_VALUE;

    @Override
    public void onCreatePreferences(Bundle bundle, String s) {

        Preferences preferences = Preferences.get();
        preferences.migrateOldPreferences();

        addPreferencesFromResource(R.xml.preferences);
        otherPrefGroup = findPreference("pref_category_other");

        repoUpdateManager = FDroidApp.getRepoUpdateManager(requireContext());

        showAntiFeaturesUpgradeCard(preferences);

        Preference aboutPreference = findPreference("pref_about");
        if (aboutPreference != null) {
            aboutPreference.setOnPreferenceClickListener(aboutPrefClickedListener);
        }

        keepInstallHistoryPref = findPreference(Preferences.PREF_KEEP_INSTALL_HISTORY);
        sendToFDroidMetricsPref =
                ObjectsCompat.requireNonNull(findPreference(Preferences.PREF_SEND_TO_FDROID_METRICS));
        sendToFDroidMetricsPref.setEnabled(keepInstallHistoryPref.isChecked());
        installHistoryPref = ObjectsCompat.requireNonNull(findPreference("installHistory"));
        installHistoryPref.setVisible(keepInstallHistoryPref.isChecked());
        if (preferences.isSendingToFDroidMetrics()) {
            installHistoryPref.setTitle(R.string.install_history_and_metrics);
        } else {
            installHistoryPref.setTitle(R.string.install_history);
        }

        useDnsCacheCheckPref = ObjectsCompat.requireNonNull(findPreference(Preferences.PREF_USE_DNS_CACHE));
        useTorCheckPref = ObjectsCompat.requireNonNull(findPreference(Preferences.PREF_USE_TOR));
        useTorCheckPref.setOnPreferenceChangeListener(useTorChangedListener);
        enableProxyCheckPref = ObjectsCompat.requireNonNull(findPreference(Preferences.PREF_ENABLE_PROXY));
        enableProxyCheckPref.setOnPreferenceChangeListener(proxyEnabledChangedListener);
        updateAutoDownloadPref = findPreference(Preferences.PREF_AUTO_DOWNLOAD_INSTALL_UPDATES);

        overWifiSeekBar = ObjectsCompat.requireNonNull(findPreference(Preferences.PREF_OVER_WIFI));
        overWifiSeekBar.setSeekBarLiveUpdater(this::getNetworkSeekBarSummary);
        overDataSeekBar = ObjectsCompat.requireNonNull(findPreference(Preferences.PREF_OVER_DATA));
        overDataSeekBar.setSeekBarLiveUpdater(this::getNetworkSeekBarSummary);
        updateIntervalSeekBar = ObjectsCompat.requireNonNull(findPreference(Preferences.PREF_UPDATE_INTERVAL));
        updateIntervalSeekBar.setSeekBarLiveUpdater(this::getUpdateIntervalSeekbarSummary);
        ipfsGateways = ObjectsCompat.requireNonNull(findPreference("ipfsGateways"));
        updateIpfsGatewaySummary();

        ListPreference languagePref = ObjectsCompat.requireNonNull(findPreference(Preferences.PREF_LANGUAGE));
        if (Build.VERSION.SDK_INT >= 24) {
            PreferenceCategory category = ObjectsCompat.requireNonNull(findPreference("pref_category_display"));
            category.removePreference(languagePref);
        } else {
            Languages languages = Languages.get((AppCompatActivity) getActivity());
            languagePref.setDefaultValue(Languages.USE_SYSTEM_DEFAULT);
            languagePref.setEntries(languages.getAllNames());
            languagePref.setEntryValues(languages.getSupportedLocales());
        }

        if (requireActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            PreferenceCategory category = ObjectsCompat.requireNonNull(findPreference(
                    "pref_category_appcompatibility"));
            category.removePreference(
                    ObjectsCompat.requireNonNull(findPreference(Preferences.PREF_FORCE_TOUCH_APPS)));
        }

        topScroller = new LinearSmoothScroller(requireActivity()) {
            @Override
            protected int getVerticalSnapPreference() {
                return SNAP_TO_START;
            }
        };
    }

    private void showAntiFeaturesUpgradeCard(@NonNull Preferences preferences) {
        if (preferences.pendingAntiFeaturesUpgrade()) {
            Preference card = new Preference(getContext()) {
                private Preferences.ChangeListener listener = () -> {
                    if (!Preferences.get().pendingAntiFeaturesUpgrade()) {
                        getPreferenceScreen().removePreference(this);
                    } else refreshPending();
                };
                private List<String> pending = null;
                private SparseIntArray meta = null;
                private Set<String> toEnable = new HashSet();
                private View.OnClickListener buttonListener = v -> {
                    if (v instanceof Button) {
                        int id = ((Button) v).getId();
                        preferences.finalizeAntiFeaturesUpgrade(getContext(),
                                id == R.id.antifeatures_enable ? toEnable : null,
                                findPreference("showAntiFeatures"));
                    }
                };
                private View contentView;

                private void refreshPending() {
                    List<String> newPending = new ArrayList();
                    SparseIntArray newMeta = new SparseIntArray();
                    Preferences.get().getPendingAntiFeaturesUpgrade(getContext(), newPending, newMeta);
                    if (!newPending.equals(pending)) {
                        pending = newPending;
                        meta = newMeta;
                        if (contentView != null) updateList(contentView);
                    }
                }

                private void updateList(@NonNull View holder) {
                    LinearLayout ll = (LinearLayout) holder.findViewById(R.id.antifeatures_list);
                    if (ll != null) {
                        for (int i = ll.getChildCount() - 1; i >= 0; i--) {
                            ll.removeView(ll.getChildAt(i));
                        }
                        LayoutInflater inflater = LayoutInflater.from(getContext());

                        String[] antiFeaturesValues = getContext().getResources()
                                .getStringArray(R.array.antifeaturesValues);
                        String[] antiFeaturesNames = getContext().getResources()
                                .getStringArray(R.array.antifeaturesNames);
                        String[] learnMoreLinks = getContext().getResources()
                                .getStringArray(R.array.antifeaturesNewDefaultsLearnMore);

                        int migration = -1, subcount = -1, subitem = -1;
                        for (int p = 0, q = pending == null ? 0 : pending.size(), m = meta.size(),
                                l = learnMoreLinks.length; p < q; p++) {
                            if (subitem >= subcount - 1) {
                                if (++migration < m) {
                                    subcount = Integer.bitCount(meta.get(migration, 0));
                                    subitem = 0;
                                }
                            } else subitem++;
                            String item = pending.get(p);
                            toEnable.add(item);
                            View view = inflater.inflate(R.layout.antifeatures_upgrade_item, ll, false);
                            TextView antiFeatureText = view.findViewById(R.id.anti_feature_text);
                            if (antiFeatureText != null) {
                                String label = item;
                                for (int i = 0, n = Math.min(antiFeaturesValues.length,
                                        antiFeaturesNames.length); i < n; i++) {
                                    if (item.equals(antiFeaturesValues[i])) {
                                        label = antiFeaturesNames[i];
                                        break;
                                    }
                                }
                                antiFeatureText.setText(label);
                            }
                            TextView antiFeatureReason = view.findViewById(R.id.anti_feature_reason);
                            if (antiFeatureReason != null) {
                                antiFeatureReason.setText(AntiFeaturesListingView
                                        .getAntiFeatureDescriptionText(getContext(), item));
                                antiFeatureReason.setVisibility(View.VISIBLE);
                            }
                            ImageView antiFeatureIcon = view.findViewById(R.id.anti_feature_icon);
                            if (antiFeatureText != null) {
                                antiFeatureIcon.setImageResource(AntiFeaturesListingView
                                        .antiFeatureIcon(getContext(), item));
                            }
                            Button learnMore = view.findViewById(R.id.learn_more);
                            if (learnMore != null) {
                                if (migration < l && subitem >= subcount - 1) {
                                    final String link = learnMoreLinks[migration];
                                    if (!link.isEmpty()) {
                                        learnMore.setOnClickListener(v -> {
                                            Intent intent = new Intent(Intent.ACTION_VIEW);
                                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                                            intent.setData(Uri.parse(link));
                                            getContext().startActivity(intent);
                                        });
                                    }
                                } else learnMore.setVisibility(View.GONE);
                            }
                            ll.addView(view);
                            if (migration < m - 1 && subitem >= subcount - 1) {
                                ll.addView(new MaterialDivider(getContext()));
                            }
                        }
                    }
                }

                private View getContentView() {
                    if (contentView == null) {
                        LayoutInflater inflater = LayoutInflater.from(getContext());
                        contentView = inflater.inflate(R.layout.antifeatures_upgrade_card, null, false);
                        refreshPending();
                        Button disableButton = contentView.findViewById(R.id.antifeatures_disable);
                        if (disableButton != null) {
                            disableButton.setOnClickListener(buttonListener);
                        }
                        Button enableButton = contentView.findViewById(R.id.antifeatures_enable);
                        if (enableButton != null) {
                            enableButton.setOnClickListener(buttonListener);
                        }
                    }
                    return contentView;
                }

                @Override
                public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
                    View view = getContentView();
                    View stub = holder.findViewById(R.id.card_content);
                    if (stub instanceof ViewGroup && stub != view.getParent()) {
                        if (view.getParent() instanceof ViewGroup) {
                            ((ViewGroup) view.getParent()).removeView(view);
                        }
                        ((ViewGroup) stub).addView(view);
                    }
                }

                @Override
                public void onAttached() {
                    super.onAttached();
                    preferences.registerAppsRequiringAntiFeaturesChangeListener(listener);
                }

                @Override
                public void onDetached() {
                    super.onDetached();
                    if (contentView != null && contentView.getParent() instanceof ViewGroup) {
                        ((ViewGroup) contentView.getParent()).post(() -> {
                            ((ViewGroup) contentView.getParent()).removeView(contentView);
                        });
                    }
                    preferences.unregisterAppsRequiringAntiFeaturesChangeListener(listener);
                }
            };
            card.setPersistent(false);
            card.setSelectable(false);
            card.setLayoutResource(R.layout.preference_card);
            card.setOrder(-1);
            getPreferenceScreen().addPreference(card);
        }
    }

    private Map<String, View.OnLongClickListener> debugSidekicks = null;
    private SparseArray<View.OnLongClickListener> debugSidekicksPos = null;

    private void refreshDebugSidekicksPos(RecyclerView recycler, boolean refresh) {
        if (recycler == null) return;
        if (debugSidekicksPos == null) debugSidekicksPos = new SparseArray(debugSidekicks.size());
        else {
            setupDebugSidekicks(true, true);
            debugSidekicksPos.clear();
        }
        recycler.post(() -> {
            for (Map.Entry<String, View.OnLongClickListener> e : debugSidekicks.entrySet()) {
                int prefPos = ((PreferenceGroup.PreferencePositionCallback) recycler.getAdapter())
                        .getPreferenceAdapterPosition(e.getKey());
                if (prefPos != RecyclerView.NO_POSITION) debugSidekicksPos.put(prefPos, e.getValue());
            }
            if (refresh) setupDebugSidekicks(true, false);
        });
    }

    @SuppressWarnings("SetTextI18n")
    private void setupDebugSidekicks(boolean postLayout, boolean clear) {
        RecyclerView recycler = getListView();
        if (recycler != null) {
            if (debugSidekicks == null) {
                debugSidekicks = new HashMap();
                debugSidekicks.put(Preferences.PREF_SHOW_ANTI_FEATURES, v -> {
                    Preferences.get().clearAntiFeaturesSchema();
                    new MaterialAlertDialogBuilder(v.getContext())
                            .setTitle("Anti-Features schema cleared")
                            .setMessage("Restart for the upgrade magic to kick in!")
                            .setPositiveButton("Close app", (d, i) -> {
                                Runtime.getRuntime().exit(0);
                            })
                            .show();
                    return true;
                });
                RecyclerView.Adapter adapter = recycler.getAdapter();
                if (adapter != null) {
                    adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                        @Override
                        public void onChanged() {
                            refreshDebugSidekicksPos(recycler, true);
                        }
                    });
                }
                refreshDebugSidekicksPos(recycler, false);
            }
            if (postLayout) {
                for (int i = 0, n = debugSidekicksPos == null ? 0 : debugSidekicksPos.size(); i < n; i++) {
                    RecyclerView.ViewHolder viewHolder = recycler
                            .findViewHolderForAdapterPosition(debugSidekicksPos.keyAt(i));
                    if (viewHolder != null) {
                        viewHolder.itemView.setOnLongClickListener(clear ? null : debugSidekicksPos.valueAt(i));
                        viewHolder.itemView.setLongClickable(!clear);
                    }
                }
            } else {
                recycler.addOnChildAttachStateChangeListener(
                        new RecyclerView.OnChildAttachStateChangeListener() {
                            private void setOnLongClickListener(@NonNull View view, final boolean onAttach) {
                                if (debugSidekicksPos == null) return;
                                View.OnLongClickListener listener = debugSidekicksPos
                                        .get(recycler.getChildAdapterPosition(view), null);
                                if (listener != null) {
                                    view.setOnLongClickListener(onAttach ? listener : null);
                                    view.setLongClickable(onAttach);
                                }
                            }

                            @Override
                            public void onChildViewAttachedToWindow(@NonNull View view) {
                                setOnLongClickListener(view, true);
                            }

                            @Override
                            public void onChildViewDetachedFromWindow(@NonNull View view) {
                                setOnLongClickListener(view, false);
                            }
                        });
            }
        }
    }

    @Override
    public RecyclerView.LayoutManager onCreateLayoutManager() {
        if (BuildConfig.DEBUG) return new LinearLayoutManager(requireContext()) {
            @Override
            public void onLayoutCompleted(RecyclerView.State state) {
                super.onLayoutCompleted(state);
                setupDebugSidekicks(true, false);
            }
        };
        return super.onCreateLayoutManager();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        repoUpdateManager.getNextUpdateLiveData().observe(getViewLifecycleOwner(), time -> {
            nextUpdateCheck = time;
            updateSummary(Preferences.PREF_UPDATE_INTERVAL, false);
        });

        if (BuildConfig.DEBUG) setupDebugSidekicks(false, false);
    }

    private void checkSummary(String key, int resId) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(resId);
        }
    }

    private void entrySummary(String key) {
        ListPreference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(pref.getEntry());
        }
    }

    private void textSummary(String key, int resId) {
        EditTextPreference pref = findPreference(key);
        if (pref == null) {
            Utils.debugLog(TAG, "null preference found for " + key);
        } else {
            pref.setSummary(getString(resId, pref.getText()));
        }
    }

    private String getNetworkSeekBarSummary(int position) {
        if (position == 0) {
            return getString(R.string.over_network_never_summary);
        } else if (position == 1) {
            return getString(R.string.over_network_on_demand_summary);
        } else if (position == 2) {
            return getString(R.string.over_network_always_summary);
        } else {
            throw new IllegalArgumentException("Unknown seekbar position");
        }
    }

    private void setNetworkSeekBarSummary(SeekBarPreference seekBarPreference) {
        int position = seekBarPreference.getValue();
        seekBarPreference.setSummary(getNetworkSeekBarSummary(position));
    }

    private void enableUpdateInterval() {
        if (overWifiSeekBar.getValue() == Preferences.OVER_NETWORK_NEVER
                && overDataSeekBar.getValue() == Preferences.OVER_NETWORK_NEVER) {
            updateIntervalSeekBar.setEnabled(false);
            updateIntervalSeekBar.setSummary(UPDATE_INTERVAL_NAMES[0]);
        } else {
            updateIntervalSeekBar.setEnabled(true);
            updateIntervalSeekBar.setSummary(getUpdateIntervalSeekbarSummary(updateIntervalSeekBar.getValue()));
        }
    }

    private String getUpdateIntervalSeekbarSummary(int position) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(UPDATE_INTERVAL_NAMES[position]));
        if (nextUpdateCheck < 0) {
            sb.append("\n");
            sb.append(getString(R.string.auto_update_time_past));
        } else if (nextUpdateCheck < Long.MAX_VALUE) {
            sb.append("\n");
            CharSequence nextUpdate = DateUtils.getRelativeTimeSpanString(nextUpdateCheck,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
            sb.append(getString(R.string.auto_update_time, nextUpdate));
        } else if (position != 0) {
            sb.append("\n");
            String never = getString(R.string.repositories_last_update_never);
            sb.append(getString(R.string.auto_update_time, never));
        }
        return sb.toString();
    }

    private void updateSummary(String key, boolean changing) {

        switch (key) {

            case Preferences.PREF_UPDATE_INTERVAL:
                updateIntervalSeekBar.setMax(Preferences.UPDATE_INTERVAL_VALUES.length - 1);
                int seekBarPosition = updateIntervalSeekBar.getValue();
                updateIntervalSeekBar.setSummary(getUpdateIntervalSeekbarSummary(seekBarPosition));
                break;

            case Preferences.PREF_OVER_WIFI:
                overWifiSeekBar.setMax(Preferences.OVER_NETWORK_ALWAYS);
                setNetworkSeekBarSummary(overWifiSeekBar);
                enableUpdateInterval();
                break;

            case Preferences.PREF_OVER_DATA:
                overDataSeekBar.setMax(Preferences.OVER_NETWORK_ALWAYS);
                setNetworkSeekBarSummary(overDataSeekBar);
                enableUpdateInterval();
                break;

            case Preferences.PREF_UPDATE_NOTIFICATION_ENABLED:
                checkSummary(key, R.string.notify_on);
                break;

            case Preferences.PREF_THEME:
                entrySummary(key);
                if (changing) {
                    FDroidApp.applyTheme();
                }
                break;

            case Preferences.PREF_USE_PURE_BLACK_DARK_THEME:
                if (changing) {
                    AppCompatActivity activity = (AppCompatActivity) getActivity();
                    // Theme will be applied upon activity creation
                    if (activity != null) {
                        ActivityCompat.recreate(activity);
                    }
                }
                break;

            case Preferences.PREF_SHOW_INCOMPAT_VERSIONS:
                checkSummary(key, R.string.show_incompat_versions_on);
                break;

            case Preferences.PREF_SHOW_ANTI_FEATURES:
                checkSummary(key, R.string.show_anti_feature_apps_on);
                break;

            case Preferences.PREF_FORCE_TOUCH_APPS:
                checkSummary(key, R.string.force_touch_apps_on);
                break;

            case Preferences.PREF_LOCAL_REPO_NAME:
                textSummary(key, R.string.local_repo_name_summary);
                break;

            case Preferences.PREF_LOCAL_REPO_HTTPS:
                checkSummary(key, R.string.local_repo_https_on);
                break;

            case Preferences.PREF_LANGUAGE:
                entrySummary(key);
                if (changing) {
                    AppCompatActivity activity = (AppCompatActivity) requireActivity();
                    Languages.setLanguage(activity);
                    FDroidApp.onLanguageChanged(activity.getApplicationContext());
                    Languages.forceChangeLanguage(activity);
                }
                break;

            case Preferences.PREF_KEEP_CACHE_TIME:
                entrySummary(key);
                if (changing
                        && currentKeepCacheTime != Preferences.get().getKeepCacheTime()) {
                    CleanCacheWorker.schedule(requireContext());
                }
                break;

            case Preferences.PREF_EXPERT:
                checkSummary(key, R.string.expert_on);
                int expertPreferencesCount = 0;
                boolean isExpertMode = Preferences.get().expertMode();
                for (int i = 0; i < otherPrefGroup.getPreferenceCount(); i++) {
                    Preference pref = otherPrefGroup.getPreference(i);
                    if (TextUtils.equals(Preferences.PREF_EXPERT, pref.getDependency())) {
                        pref.setVisible(isExpertMode);
                        expertPreferencesCount++;
                    }
                }
                if (changing) {
                    RecyclerView recyclerView = getListView();
                    int preferencesCount = recyclerView.getAdapter().getItemCount();
                    if (!isExpertMode) {
                        expertPreferencesCount = 0;
                    }
                    topScroller.setTargetPosition(preferencesCount - expertPreferencesCount - 1);
                    recyclerView.getLayoutManager().startSmoothScroll(topScroller);
                }
                break;

            case Preferences.PREF_PRIVILEGED_INSTALLER:
                // We may have removed this preference if it is not suitable to show the user.
                // So lets check it is here first.
                final SwitchPreferenceCompat pref = findPreference(
                        Preferences.PREF_PRIVILEGED_INSTALLER);
                if (pref != null) {
                    checkSummary(key, R.string.system_installer_on);
                }
                break;

            case Preferences.PREF_ENABLE_PROXY:
                SwitchPreferenceCompat checkPref = ObjectsCompat.requireNonNull(findPreference(key));
                checkPref.setSummary(R.string.enable_proxy_summary);
                break;

            case Preferences.PREF_PROXY_HOST:
                EditTextPreference textPref = ObjectsCompat.requireNonNull(findPreference(key));
                String text = Preferences.get().getProxyHost();
                if (TextUtils.isEmpty(text) || text.equals(Preferences.DEFAULT_PROXY_HOST)) {
                    textPref.setSummary(R.string.proxy_host_summary);
                } else {
                    textPref.setSummary(text);
                }
                break;

            case Preferences.PREF_PROXY_PORT:
                EditTextPreference textPref2 = ObjectsCompat.requireNonNull(findPreference(key));
                int port = Preferences.get().getProxyPort();
                if (port == Preferences.DEFAULT_PROXY_PORT) {
                    textPref2.setSummary(R.string.proxy_port_summary);
                } else {
                    textPref2.setSummary(String.valueOf(port));
                }
                break;

            case Preferences.PREF_KEEP_INSTALL_HISTORY:
                if (keepInstallHistoryPref.isChecked()) {
                    InstallHistoryService.register(getActivity());
                    installHistoryPref.setVisible(true);
                    sendToFDroidMetricsPref.setEnabled(true);
                } else {
                    InstallHistoryService.unregister(getActivity());
                    installHistoryPref.setVisible(false);
                    sendToFDroidMetricsPref.setEnabled(false);
                }
                setFDroidMetricsWorker();
                break;

            case Preferences.PREF_SEND_TO_FDROID_METRICS:
                setFDroidMetricsWorker();
                break;
        }
    }

    private void setFDroidMetricsWorker() {
        if (sendToFDroidMetricsPref.isEnabled() && sendToFDroidMetricsPref.isChecked()) {
            FDroidMetricsWorker.schedule(getContext());
        } else {
            FDroidMetricsWorker.cancel(getContext());
        }
    }

    private void updateIpfsGatewaySummary() {
        Preferences prefs = Preferences.get();
        if (prefs.isIpfsEnabled()) {
            int cnt = prefs.getActiveIpfsGateways().size();
            ipfsGateways.setSummary(getResources().getQuantityString(R.plurals.ipfsgw_summary, cnt, cnt));
        } else {
            ipfsGateways.setSummary(getString(R.string.ipfsgw_summary_disabled));
        }
    }

    /**
     * About dialog click listener
     * <p>
     * TODO: this might need to be changed when updated to the new preference pattern
     */

    private final Preference.OnPreferenceClickListener aboutPrefClickedListener = preference -> {
        final View view = getLayoutInflater().inflate(R.layout.about, null);
        final Context context = requireContext();

        String versionName = Utils.getVersionName(context);
        if (versionName != null) {
            TextView versionNameView = view.findViewById(R.id.version);
            versionNameView.setText(versionName);
            versionNameView.setOnLongClickListener(v -> {
                throw new RuntimeException("BOOM!");
            });
        }
        new MaterialAlertDialogBuilder(context)
                .setView(view)
                .setPositiveButton(R.string.ok, null)
                .show();
        return true;
    };

    /**
     * Initializes SystemInstaller preference, which can only be enabled when F-Droid is installed as a system-app
     */
    private void initPrivilegedInstallerPreference() {
        final SwitchPreferenceCompat pref = findPreference(Preferences.PREF_PRIVILEGED_INSTALLER);

        // This code will be run each time the activity is resumed, and so we may have already removed
        // this preference.
        if (pref == null) {
            return;
        }

        Preferences p = Preferences.get();
        boolean enabled = p.isPrivilegedInstallerEnabled();
        boolean installed = PrivilegedInstaller.isExtensionInstalledCorrectly(getActivity())
                == PrivilegedInstaller.IS_EXTENSION_INSTALLED_YES;

        // On later versions of Android the privileged installer needs to be installed
        // via flashing an update.zip or building into a rom. As such, if it isn't installed
        // by the time the user boots, opens F-Droid, and views this settings page, then there
        // is no benefit showing it to them (it will only be disabled and we can't offer any
        // way to easily install from here.
        if (!installed) {
            otherPrefGroup.removePreference(pref);
        } else {
            pref.setEnabled(true);
            pref.setDefaultValue(true);
            pref.setChecked(enabled);
            pref.setOnPreferenceClickListener(preference -> {
                SharedPreferences.Editor editor = pref.getSharedPreferences().edit();
                if (pref.isChecked()) {
                    editor.remove(Preferences.PREF_PRIVILEGED_INSTALLER);
                } else {
                    editor.putBoolean(Preferences.PREF_PRIVILEGED_INSTALLER, false);
                }
                editor.apply();
                return true;
            });
        }
    }

    /**
     * If a user specifies they want to fetch updates automatically, then start the download of relevant
     * updates as soon as they enable the feature.
     * Also, if auto-update is available then change the label to indicate that it
     * will actually _install_ apps, not just fetch their .apk file automatically.
     */
    private void initAutoFetchUpdatesPreference() {
        updateAutoDownloadPref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (newValue instanceof Boolean && (boolean) newValue) {
                AppUpdateStatusManager.getInstance(getActivity()).checkForUpdatesAndInstall();
            }
            return true;
        });

        if (PrivilegedInstaller.isDefault(getActivity()) || SessionInstallManager.canBeUsed(getContext())) {
            updateAutoDownloadPref.setTitle(R.string.update_auto_install);
            updateAutoDownloadPref.setSummary(R.string.update_auto_install_summary);
        }
    }

    private void initUseDnsCachePreference() {
        useDnsCacheCheckPref.setDefaultValue(false);
        useDnsCacheCheckPref.setChecked(Preferences.get().isDnsCacheEnabled());
    }

    /**
     * The default for "Use Tor" is dynamically set based on whether Orbot is installed.
     */
    private void initUseTorPreference(Context context) {
        useTorCheckPref.setDefaultValue(OrbotHelper.isOrbotInstalled(context));
        useTorCheckPref.setChecked(Preferences.get().isTorEnabled());
    }

    private final Preference.OnPreferenceChangeListener useTorChangedListener =
            new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(@NonNull Preference preference, Object enabled) {
                    if ((Boolean) enabled) {
                        enableProxyCheckPref.setChecked(false);
                        final AppCompatActivity activity = (AppCompatActivity) requireActivity();
                        if (!OrbotHelper.isOrbotInstalled(activity)) {
                            Intent intent = OrbotHelper.getOrbotInstallIntent(activity);
                            activity.startActivityForResult(intent, REQUEST_INSTALL_ORBOT);
                        }
                        // NetCipher gets configured to use Tor in onPause()
                        // via a call to FDroidApp.configureProxy()
                    }
                    return true;
                }
            };

    private final Preference.OnPreferenceChangeListener proxyEnabledChangedListener =
            new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(@NonNull Preference preference, Object enabled) {
                    if ((Boolean) enabled) {
                        useTorCheckPref.setChecked(false);
                    }
                    return true;
                }
            };

    @Override
    public void onResume() {
        super.onResume();

        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);

        for (final String key : SUMMARIES_TO_UPDATE) {
            updateSummary(key, false);
        }

        currentKeepCacheTime = Preferences.get().getKeepCacheTime();

        initAutoFetchUpdatesPreference();
        initPrivilegedInstallerPreference();
        initUseDnsCachePreference();
        initUseTorPreference(requireContext().getApplicationContext());

        updateIpfsGatewaySummary();
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        FDroidApp.configureProxy(Preferences.get());
    }

    @Override
    public void onSharedPreferenceChanged(
            SharedPreferences sharedPreferences, String key) {
        updateSummary(key, true);
        //noinspection IfCanBeSwitch
        if (key.equals(Preferences.PREF_PREVENT_SCREENSHOTS)) {
            if (Preferences.get().preventScreenshots()) {
                requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        } else if (Preferences.PREF_SEND_TO_FDROID_METRICS.equals(key)) {
            if (Preferences.get().isSendingToFDroidMetrics()) {
                String msg = getString(R.string.toast_metrics_in_install_history,
                        requireContext().getString(R.string.app_name));
                Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
                installHistoryPref.setTitle(R.string.install_history_and_metrics);
                Intent intent = new Intent(getActivity(), InstallHistoryActivity.class);
                intent.putExtra(InstallHistoryActivity.EXTRA_SHOW_FDROID_METRICS, true);
                startActivity(intent);
            } else {
                installHistoryPref.setTitle(R.string.install_history);
            }
        } else if (Preferences.PREF_OVER_DATA.equals(key) || Preferences.PREF_OVER_WIFI.equals(key)) {
            if (glideRequestManager == null) {
                glideRequestManager = Glide.with(requireContext());
            }
            glideRequestManager.applyDefaultRequestOptions(new RequestOptions()
                    .onlyRetrieveFromCache(Preferences.get().isBackgroundDownloadAllowed()));
            RepoUpdateWorker.scheduleOrCancel(requireContext());
        } else if (Preferences.PREF_UPDATE_INTERVAL.equals(key)) {
            RepoUpdateWorker.scheduleOrCancel(requireContext());
        }
    }
}
