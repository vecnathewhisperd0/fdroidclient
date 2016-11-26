package org.belos.belmarket.compat;

import android.net.Uri;
import android.os.Build;

public class UriCompat {

    /**
     * Uri#getQueryParameter(String) has the following warning:
     *
     *  > Prior to Ice Cream Sandwich, this decoded the '+' character as '+' rather than ' '.
     */
    public static String getQueryParameter(Uri uri, String key) {
        String value = uri.getQueryParameter(key);
        if (value != null && Build.VERSION.SDK_INT < 14) {
            value = value.replaceAll("\\+", " ");
        }
        return value;
    }

}
