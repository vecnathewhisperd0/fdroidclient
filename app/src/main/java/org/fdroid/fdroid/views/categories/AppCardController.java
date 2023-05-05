package org.fdroid.fdroid.views.categories;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.fdroid.fdroid.R;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.views.AppDetailsActivity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.fdroid.database.AppOverviewItem;

/**
 * The {@link AppCardController} can bind an app to several different layouts, as long as the layout
 * contains the following elements:
 * + {@link R.id#icon} ({@link ImageView}, required)
 * + {@link R.id#summary} ({@link TextView}, required)
 * + {@link R.id#new_tag} ({@link TextView}, optional)
 */
public class AppCardController extends RecyclerView.ViewHolder {
    /**
     * After this many days, don't consider showing the "New" tag next to an app.
     */
    public static final int DAYS_TO_CONSIDER_NEW = 14;

    @NonNull
    private final ImageView icon;

    /**
     * Text starting with the app name (in bold) followed by a short summary of the app.
     */
    @NonNull
    private final TextView summary;

    /**
     * A little blue tag which says "New" to indicate an app was added to the repository recently.
     */
    @Nullable
    private final TextView newTag;

    @Nullable
    private AppOverviewItem currentApp;

    public AppCardController(AppCompatActivity activity, View itemView) {
        super(itemView);

        icon = ViewCompat.requireViewById(itemView, R.id.icon);
        summary = ViewCompat.requireViewById(itemView, R.id.summary);

        newTag = itemView.findViewById(R.id.new_tag);

        itemView.setOnClickListener(v -> {
            if (currentApp == null) {
                return;
            }

            Intent intent = new Intent(activity, AppDetailsActivity.class);
            intent.putExtra(AppDetailsActivity.EXTRA_APPID, currentApp.getPackageName());
            Pair<View, String> iconTransitionPair = Pair.create(icon,
                    activity.getString(R.string.transition_app_item_icon));

            // unchecked since the right type is passed as 2nd varargs arg: Pair<View, String>
            @SuppressWarnings("unchecked")
            Bundle b = ActivityOptionsCompat.makeSceneTransitionAnimation(activity,
                            iconTransitionPair)
                    .toBundle();
            ContextCompat.startActivity(activity, intent, b);
        });
    }

    public void bindApp(@NonNull AppOverviewItem app) {
        currentApp = app;

        String name = app.getName();
        summary.setText(Utils.formatAppNameAndSummary(name == null ? "" : name, app.getSummary()));

        if (newTag != null) {
            if (isConsideredNew(app)) {
                newTag.setVisibility(View.VISIBLE);
            } else {
                newTag.setVisibility(View.GONE);
            }
        }
        Utils.setIconFromRepoOrPM(app, icon, icon.getContext());
    }

    private boolean isConsideredNew(@NonNull AppOverviewItem app) {
        if (app.getAdded() != app.getLastUpdated()) {
            return false;
        }
        return Utils.daysSince(app.getAdded()) <= DAYS_TO_CONSIDER_NEW;
    }
}
