package org.fdroid.fdroid.views.categories;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.fdroid.database.AppOverviewItem;
import org.fdroid.database.FDroidDatabase;
import org.fdroid.fdroid.R;
import org.fdroid.fdroid.data.DBHelper;
import org.fdroid.fdroid.views.apps.AppListActivity;
import org.fdroid.fdroid.views.apps.FeatureImage;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class CategoryController extends RecyclerView.ViewHolder {
    private final Button viewAll;
    private final TextView heading;
    private final FeatureImage image;
    private final AppPreviewAdapter appCardsAdapter;
    private final FrameLayout background;

    private final AppCompatActivity activity;
    private final FDroidDatabase db;
    static final int NUM_OF_APPS_PER_CATEGORY_ON_OVERVIEW = 20;

    private String currentCategory;
    @Nullable
    private Disposable disposable;

    CategoryController(final AppCompatActivity activity, View itemView) {
        super(itemView);

        this.activity = activity;
        db = DBHelper.getDb(activity);

        appCardsAdapter = new AppPreviewAdapter(activity);

        viewAll = itemView.findViewById(R.id.view_all_button);
        viewAll.setOnClickListener(v -> {
            if (currentCategory == null) {
                return;
            }

            Intent intent = new Intent(CategoryController.this.activity, AppListActivity.class);
            intent.putExtra(AppListActivity.EXTRA_CATEGORY, currentCategory);
            CategoryController.this.activity.startActivity(intent);
        });

        heading = itemView.findViewById(R.id.name);
        image = itemView.findViewById(R.id.category_image);
        background = itemView.findViewById(R.id.category_background);

        RecyclerView appCards = itemView.findViewById(R.id.app_cards);
        appCards.setAdapter(appCardsAdapter);
        appCards.addItemDecoration(new ItemDecorator(activity));
    }

    public static String translateCategory(Context context, String categoryName) {
        int categoryNameId = getCategoryResource(context, categoryName, "string", false);
        return categoryNameId == 0 ? categoryName : context.getString(categoryNameId);
    }

    void bindModel(@NonNull String categoryName, LiveData<List<AppOverviewItem>> liveData) {
        loadAppItems(liveData);
        currentCategory = categoryName;

        String translatedName = translateCategory(activity, categoryName);
        heading.setText(translatedName);
        heading.setContentDescription(activity.getString(R.string.tts_category_name, translatedName));

        viewAll.setVisibility(View.INVISIBLE);
        loadNumAppsInCategory();

        @ColorInt int backgroundColour = getBackgroundColour(activity, categoryName);
        background.setBackgroundColor(backgroundColour);

        int categoryImageId = getCategoryResource(activity, categoryName, "drawable", true);
        if (categoryImageId == 0) {
            image.setColour(backgroundColour);
            image.setImageDrawable(null);
        } else {
            image.setColour(ContextCompat.getColor(activity, R.color.fdroid_blue));
            Glide.with(activity).load(categoryImageId).into(image);
        }
    }

    private void loadAppItems(LiveData<List<AppOverviewItem>> liveData) {
        setIsRecyclable(false);
        liveData.observe(activity, new Observer<List<AppOverviewItem>>() {
            @Override
            public void onChanged(List<AppOverviewItem> items) {
                appCardsAdapter.setAppCursor(items);
                setIsRecyclable(true);
                liveData.removeObserver(this);
            }
        });
    }

    private void loadNumAppsInCategory() {
        if (disposable != null) disposable.dispose();
        disposable = Single.fromCallable(() -> db.getAppDao().getNumberOfAppsInCategory(currentCategory))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::setNumAppsInCategory);
    }

    /**
     * @param requiresLowerCaseId Previously categories were translated using strings such as "category_Reading"
     *                            for the "Reading" category. Now we also need to have drawable resources such as
     *                            "category_reading". Note how drawables must have only lower case letters, whereas
     *                            we already have upper case letters in strings.xml. Hence this flag.
     */
    private static int getCategoryResource(Context context, @NonNull String categoryName, String resourceType,
                                           boolean requiresLowerCaseId) {
        String suffix = categoryName.replace(" & ", "_").replace(" ", "_").replace("'", "");
        if (requiresLowerCaseId) {
            suffix = suffix.toLowerCase(Locale.ENGLISH);
        }
        return context.getResources().getIdentifier("category_" + suffix, resourceType, context.getPackageName());
    }

    public static int getBackgroundColour(Context context, @NonNull String categoryName) {
        int colourId = getCategoryResource(context, categoryName, "color", true);
        if (colourId > 0) {
            return ContextCompat.getColor(context, colourId);
        }

        // Seed based on the categoryName, so that each time we try to choose a colour for the same
        // category it will look the same for each different user, and each different session.
        Random random = new Random(categoryName.toLowerCase(Locale.ENGLISH).hashCode());

        float[] hsv = new float[3];
        hsv[0] = random.nextFloat() * 360;
        hsv[1] = 0.4f;
        hsv[2] = 0.5f;
        return Color.HSVToColor(hsv);
    }

    private void setNumAppsInCategory(int numAppsInCategory) {
        viewAll.setVisibility(View.VISIBLE);
        Resources r = activity.getResources();
        viewAll.setText(r.getQuantityString(R.plurals.button_view_all_apps_in_category, numAppsInCategory,
                numAppsInCategory));
        viewAll.setContentDescription(r.getQuantityString(R.plurals.tts_view_all_in_category, numAppsInCategory,
                numAppsInCategory, currentCategory));
    }

    /**
     * Applies excessive padding to the start of the first item. This is so that the category artwork
     * can peek out and make itself visible. This is RTL friendly.
     *
     * @see org.fdroid.fdroid.R.dimen#category_preview__app_list__padding__horizontal
     * @see org.fdroid.fdroid.R.dimen#category_preview__app_list__padding__horizontal__first
     */
    private static class ItemDecorator extends RecyclerView.ItemDecoration {
        private final Context context;

        ItemDecorator(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public void getItemOffsets(Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                                   @NonNull RecyclerView.State state) {
            Resources r = context.getResources();
            int horizontalPadding = (int) r.getDimension(R.dimen.category_preview__app_list__padding__horizontal);
            int horizontalPaddingFirst = (int) r.getDimension(
                    R.dimen.category_preview__app_list__padding__horizontal__first);
            int horizontalPaddingLast = (int) r.getDimension(
                    R.dimen.category_preview__app_list__padding__horizontal__last);
            boolean isLtr = ViewCompat.getLayoutDirection(parent) == ViewCompat.LAYOUT_DIRECTION_LTR;
            int itemPosition = parent.getChildLayoutPosition(view);
            boolean first = itemPosition == 0;
            boolean end = itemPosition == NUM_OF_APPS_PER_CATEGORY_ON_OVERVIEW - 1;

            // Leave this "paddingEnd" local variable here for clarity when converting from
            // left/right to start/end for RTL friendly layout.
            // noinspection UnnecessaryLocalVariable
            int paddingEnd = end ? horizontalPaddingLast : horizontalPadding;
            int paddingStart = first ? horizontalPaddingFirst : horizontalPadding;

            int paddingLeft = isLtr ? paddingStart : paddingEnd;
            int paddingRight = isLtr ? paddingEnd : paddingStart;
            outRect.set(paddingLeft, 0, paddingRight, 0);
        }
    }
}
