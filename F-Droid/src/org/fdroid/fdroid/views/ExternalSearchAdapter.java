package org.fdroid.fdroid.views;

import android.content.Context;
import android.database.Cursor;
import android.support.v4.widget.CursorAdapter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.fdroid.fdroid.R;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.data.ExternalSearch;
import org.fdroid.fdroid.data.Repo;

public class ExternalSearchAdapter extends CursorAdapter {
    private static final String TAG = "ExternalSearchAdapter";

    public interface EnabledListener {
        void onSetEnabled(Repo repo, boolean isEnabled);
    }

    private final LayoutInflater inflater;

    public ExternalSearchAdapter(Context context, Cursor c, int flags) {
        super(context, c, flags);
        inflater = LayoutInflater.from(context);
    }

    public ExternalSearchAdapter(Context context, Cursor c, boolean autoRequery) {
        super(context, c, autoRequery);
        inflater = LayoutInflater.from(context);
    }

    public ExternalSearchAdapter(Context context, Cursor c) {
        super(context, c);
        inflater = LayoutInflater.from(context);
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        View view = inflater.inflate(R.layout.external_search_item, parent, false);
        setupView(cursor, view);
        return view;
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        setupView(cursor, view);
    }

    private void setupView(Cursor cursor, View view) {
        final ExternalSearch externalSearch = new ExternalSearch(cursor);
        //TODO remove
        Utils.debugLog(TAG, "setting up view for cursor: " + externalSearch.getName() + ".");

        TextView nameView = (TextView)view.findViewById(R.id.external_search_name);
        nameView.setText(externalSearch.getName());

        TextView addressView = (TextView)view.findViewById(R.id.external_search_address);
        addressView.setText(externalSearch.getAddress());

        TextView descriptionView = (TextView)view.findViewById(R.id.external_search_description);
        descriptionView.setText(externalSearch.getDescription());
    }
}
