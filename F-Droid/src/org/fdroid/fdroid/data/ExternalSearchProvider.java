package org.fdroid.fdroid.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.provider.BaseColumns;
import android.util.Log;
import android.support.annotation.Nullable;

import org.fdroid.fdroid.Utils;


/** A lot of the following code basically duplicates code from RepoProvider.
 * This could be obviously factored out, but I would rather have it separate
 * to avoid future changes from braking the code...
 */
public class ExternalSearchProvider extends FDroidProvider {
    private static final String TAG = "ExternalSearchProvider";
    private static final String PROVIDER_NAME = "ExternalSearchProvider";

    private static final UriMatcher matcher = new UriMatcher(-1);

    public interface DataColumns extends BaseColumns {

        String ADDRESS      = "address";
        String NAME         = "name";
        String DESCRIPTION  = "description";

        String[] ALL = {
                _ID, ADDRESS, NAME, DESCRIPTION,
        };
    }

    public static final class Helper {
        private static final String TAG = "ExternalSearchProvider.Helper";

        private Helper() {}

        public static Uri insert(Context context, ContentValues values) {
            ContentResolver resolver = context.getContentResolver();
            Uri uri = ExternalSearchProvider.getContentUri();
            return resolver.insert(uri, values);
        }
        public static void update(Context context, ContentValues values,
                                  ExternalSearch externalSearch)
        {
            long externalSearchId = externalSearch.getId();
            ContentResolver resolver = context.getContentResolver();
            Uri uri = ExternalSearchProvider.getContentUri(externalSearchId);
            resolver.update(
                    uri,
                    values,
                    DataColumns._ID + " = ?",
                    new String[]{ Long.toString(externalSearchId)
            });
            externalSearch.setValues(values);
        }

        public static void remove(Context context, long externalSearchId) {
            Utils.debugLog(TAG, "Removing id " + externalSearchId);
            ContentResolver resolver = context.getContentResolver();
            Uri uri = ExternalSearchProvider.getContentUri(externalSearchId);
            resolver.delete(uri, null, null);
        }

    }

    static {
        matcher.addURI(AUTHORITY + "." + PROVIDER_NAME, null, CODE_LIST);
        matcher.addURI(AUTHORITY + "." + PROVIDER_NAME, "#", CODE_SINGLE);
    }

    public static Uri getContentUri() {
        //TODO remove
        Utils.debugLog(TAG, "Opening: content://" + AUTHORITY + "." + PROVIDER_NAME);
        return Uri.parse("content://" + AUTHORITY + "." + PROVIDER_NAME);
    }

    public static Uri getContentUri(long externalSearchId) {
        return ContentUris.withAppendedId(getContentUri(), externalSearchId);
    }

    @Override
    protected String getTableName() {
        return DBHelper.TABLE_EXT_SEARCH;
    }

    @Override
    protected String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    protected UriMatcher getMatcher() {
        return matcher;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {

        if (TextUtils.isEmpty(sortOrder)) {
            sortOrder = "_ID ASC";
        }

        switch (matcher.match(uri)) {
        case CODE_LIST:
            // Do nothing (don't restrict query)
            break;

        case CODE_SINGLE:
            selection = ( selection == null ? "" : selection + " AND " ) +
                    DataColumns._ID + " = " + uri.getLastPathSegment();
            break;

        default:
            Log.e(TAG, "Invalid URI for ExternalSerach content provider: " + uri);
            throw new UnsupportedOperationException("Invalid URI for ExternalSerach content provider: " + uri);
        }

        Cursor cursor = read().query(getTableName(), projection, selection,
                selectionArgs, null, null, sortOrder);
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }


    @Nullable
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (!values.containsKey(DataColumns.ADDRESS)) {
            throw new UnsupportedOperationException("Cannot add ExternalSerach without an address.");
        }

        if (!values.containsKey(DataColumns.NAME)) {
            final String address = values.getAsString(DataColumns.ADDRESS);
            values.put(DataColumns.NAME, Utils.addressToName(address, false));
        }

        long id = write().insertOrThrow(getTableName(), null, values);
        Utils.debugLog(TAG, "Inserted external search. Notifying provider change: '" + uri + "'.");
        getContext().getContentResolver().notifyChange(uri, null);

        return getContentUri(id);
    }

    @Override
    public int delete(Uri uri, String where, String[] whereArgs) {
        switch (matcher.match(uri)) {
        case CODE_LIST:
            // Don't support deleting of multiple ExternalSeraches.
            return 0;

        case CODE_SINGLE:
            where = ( where == null ? "" : where + " AND " ) +
                    "_ID = " + uri.getLastPathSegment();
            break;

        default:
            Log.e(TAG, "Invalid URI for ExternalSerach content provider: " + uri);
            throw new UnsupportedOperationException("Invalid URI for ExternalSerach content provider: " + uri);
        }

        int rowsAffected = write().delete(getTableName(), where, whereArgs);
        Utils.debugLog(TAG, "Deleted ExternalSeraches. Notifying provider change: '" + uri + "'.");
        getContext().getContentResolver().notifyChange(uri, null);
        return rowsAffected;
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        int numRows = write().update(getTableName(), values, where, whereArgs);
        Utils.debugLog(TAG, "Updated ExternalSerach. Notifying provider change: '" + uri + "'.");
        getContext().getContentResolver().notifyChange(uri, null);
        return numRows;
    }
}
