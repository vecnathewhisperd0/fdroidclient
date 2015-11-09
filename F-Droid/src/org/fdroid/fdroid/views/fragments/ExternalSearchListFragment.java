package org.fdroid.fdroid.views.fragments;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.data.ExternalSearch;
import org.fdroid.fdroid.data.ExternalSearchProvider;
import org.fdroid.fdroid.views.ExternalSearchAdapter;

public class ExternalSearchListFragment extends ListFragment
        implements LoaderManager.LoaderCallbacks<Cursor> {
    private static final String TAG = "ExternalSearchListFragment";

    private ExternalSearchAdapter externalSearchAdapter;
    private OnItemLongClickListener onItemLongClickListener = null;
    private OnListItemClickListener onListItemClickListener = null;

    /* Remembers, if the view has been created already. If it has not, registering an
     * OnListItemClickListener will not work.
     */
    private boolean viewCreated = false;

    @Override
    public Loader<Cursor> onCreateLoader(int i, Bundle bundle) {
        Uri uri = ExternalSearchProvider.getContentUri();
        Utils.debugLog(TAG, "Creating external search loader '" + uri + "'.");
        final String[] projection = {
                ExternalSearchProvider.DataColumns._ID,
                ExternalSearchProvider.DataColumns.NAME,
                ExternalSearchProvider.DataColumns.ADDRESS,
                ExternalSearchProvider.DataColumns.DESCRIPTION
        };
        return new CursorLoader(getActivity(), uri, projection, null, null, null);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);
        setHasOptionsMenu(true);

        externalSearchAdapter = new ExternalSearchAdapter(getActivity(), null);
        //externalSearchAdapter.setEnabledListener(this);
        setListAdapter(externalSearchAdapter);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> cursorLoader) {
        externalSearchAdapter.swapCursor(null);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Starts a new or restarts an existing Loader in this manager
        getLoaderManager().restartLoader(0, null, this);
    }


    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        if (onListItemClickListener != null) {
            final ExternalSearch externalSearch = getSearchByPosition(position);
            onListItemClickListener.onListItemClick(l, v, position, id, externalSearch);
        }
        super.onListItemClick(l, v, position, id);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewCreated = true;

        if (onItemLongClickListener != null) {
            registerOnItemLongClickListener();
        }
    }

    /**
     * Sets an {@see AdapterView.OnItemLongClickListener}.
     * Since the Listener can not be registered in the ListView until the ListView has been
     * created, the actual setting of the listener might be postponed until creation.
     *
     * @param listener the AdapterView.OnItemLongClickListener.
     * @return true, if the Listener could be registered immediately, false if registration has
     * been postponed.
     */
    public boolean setOnItemLongClickListener(OnItemLongClickListener listener) {
        boolean rv = false;
        onItemLongClickListener = listener;
        if (viewCreated && listener != null) {
            registerOnItemLongClickListener();
            rv = true;
        }
        return rv;
    }

    public void setOnListItemClickListener(OnListItemClickListener listener) {
        onListItemClickListener = listener;
    }

    private void registerOnItemLongClickListener() {
        ListView lv = null;
        // We can't get the ListView before the view is created...
        if (viewCreated) {
            try {
                lv = super.getListView();
            } catch (Exception e) {
                //TODO
                Utils.debugLog(TAG, "Exception: " + e.toString());
            }
        }

        if (lv != null) {
            if (onItemLongClickListener != null) {
                lv.setLongClickable(true);
                lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                    @Override
                    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                        ExternalSearch externalSearch = getSearchByPosition(position);
                        onItemLongClickListener.onItemLongClick(parent, view,
                                position, id, externalSearch);
                        return true;
                    }
                });
            } else {
                lv.setOnItemLongClickListener(null);
            }
        }
    }

    @Override
    public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
        externalSearchAdapter.swapCursor(cursor);
    }

    private ExternalSearch getSearchByPosition(int position) {
        return new ExternalSearch((Cursor) externalSearchAdapter.getItem(position));
    }

    public interface OnListItemClickListener {
        void onListItemClick(ListView l, View v, int position, long id, ExternalSearch externalSearch);
    }

    public interface OnItemLongClickListener {
        boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id, ExternalSearch externalSearch);
    }
}