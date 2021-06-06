package org.fdroid.fdroid.net;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.module.AppGlideModule;

@GlideModule
public class FDroidGlideModule extends AppGlideModule {
    @Override
    public void applyOptions(@NonNull Context context, GlideBuilder builder) {
        builder.setDefaultTransitionOptions(Drawable.class,
                DrawableTransitionOptions.withCrossFade()).setDefaultTransitionOptions(Bitmap.class,
                BitmapTransitionOptions.withCrossFade());
    }
}
