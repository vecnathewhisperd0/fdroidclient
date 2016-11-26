package org.belos.belmarket.views.fragments;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.belos.belmarket.R;
import org.belos.belmarket.compat.CursorAdapterCompat;
import org.belos.belmarket.data.AppProvider;
import org.belos.belmarket.views.AppListAdapter;
import org.belos.belmarket.views.CanUpdateAppListAdapter;

public class CanUpdateAppsFragment extends AppListFragment {

    @Override
    protected AppListAdapter getAppListAdapter() {
        return CanUpdateAppListAdapter.create(getActivity(), null, CursorAdapterCompat.FLAG_AUTO_REQUERY);
    }

    @Override
    protected String getFromTitle() {
        return getString(R.string.tab_updates);
    }

    @Override
    protected Uri getDataUri() {
        return AppProvider.getCanUpdateUri();
    }

    @Override
    protected Uri getDataUri(String query) {
        return AppProvider.getSearchCanUpdateUri(query);
    }

    @Override
    protected int getEmptyMessage() {
        return R.string.empty_can_update_app_list;
    }

    @Override
    protected int getNoSearchResultsMessage() {
        return R.string.empty_search_can_update_app_list;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.can_update_app_list, container, false);
    }

}
