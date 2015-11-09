package org.fdroid.fdroid.data;

import android.content.ContentValues;
import android.database.Cursor;

import org.fdroid.fdroid.Utils;

import java.net.URLEncoder;

public class ExternalSearch extends ValueObject {
    private static final String TAG = "ExternalSearch";
    protected long id;

    public String address;
    public String name;
    public String description;

    public ExternalSearch() { }


    public ExternalSearch(Cursor cursor) {

        checkCursorPosition(cursor);

        for (int i = 0; i < cursor.getColumnCount(); i++) {
            switch (cursor.getColumnName(i)) {
                case ExternalSearchProvider.DataColumns._ID:
                    id = cursor.getInt(i);
                    break;
                case ExternalSearchProvider.DataColumns.ADDRESS:
                    address = cursor.getString(i);
                    break;
                case ExternalSearchProvider.DataColumns.NAME:
                    name = cursor.getString(i);
                    break;
                case ExternalSearchProvider.DataColumns.DESCRIPTION:
                    description = cursor.getString(i);
                    break;
            }
        }
    }

    private static int toInt(Integer value) {
        return (value == null) ? 0 : value;
    }

    public void setValues(ContentValues values) {
        if (values.containsKey(ExternalSearchProvider.DataColumns._ID)) {
            id = toInt(values.getAsInteger(ExternalSearchProvider.DataColumns._ID));
        }

        if (values.containsKey(ExternalSearchProvider.DataColumns.ADDRESS)) {
            address = values.getAsString(ExternalSearchProvider.DataColumns.ADDRESS);
        }

        if (values.containsKey(ExternalSearchProvider.DataColumns.NAME)) {
            name = values.getAsString(ExternalSearchProvider.DataColumns.NAME);
        }

        if (values.containsKey(ExternalSearchProvider.DataColumns.DESCRIPTION)) {
            description = values.getAsString(ExternalSearchProvider.DataColumns.DESCRIPTION);
        }
    }

    @Override
    public String toString() { return address; }

    public String getName() { return name; }

    public String getAddress() { return address; }

    public String getDescription() { return description; }

    public long getId() { return id; }

    public String generateUri(String searchQuery) {
        Utils.debugLog(TAG, "Appending query: " + searchQuery);
        String query = "";
        try {
            query = URLEncoder.encode(searchQuery, "utf-8");
        } catch (Exception e) {
            //TODO ?
            Utils.debugLog(TAG, "Exception in URL encode." + e.toString());
        }
        Utils.debugLog(TAG, "Appending encoded query: " + query);
        return address.replace("$s", query);
    }

}
