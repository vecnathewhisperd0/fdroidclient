package org.fdroid.fdroid.views.updates.items;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Used as a common base class for all data types in the {@link
 * org.fdroid.fdroid.views.updates.UpdatesAdapter}. Doesn't have any
 * functionality of its own, but allows the {@link
 * org.fdroid.fdroid.views.updates.UpdatesAdapter#delegatesManager}
 * to specify a data type more specific than just {@link Object}.
 */
public abstract class AppUpdateData {
    public final AppCompatActivity activity;

    public AppUpdateData(AppCompatActivity activity) {
        this.activity = activity;
    }
}
