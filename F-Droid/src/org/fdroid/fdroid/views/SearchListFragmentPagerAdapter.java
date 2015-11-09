package org.fdroid.fdroid.views;

import android.content.Intent;
import android.net.Uri;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentPagerAdapter;
import android.view.View;
import android.widget.ListView;

import org.fdroid.fdroid.SearchResults;
import org.fdroid.fdroid.R;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.data.ExternalSearch;
import org.fdroid.fdroid.views.fragments.ExternalSearchListFragment;
import org.fdroid.fdroid.views.fragments.SearchResultsFragment;


public class SearchListFragmentPagerAdapter extends FragmentPagerAdapter {
    private static final String TAG = "SearchListFragmentPagerAdapter";
    private final SearchResults parent;

    public SearchListFragmentPagerAdapter(SearchResults parent) {
        super(parent.getSupportFragmentManager());
        this.parent = parent;
    }

    @Override
    public Fragment getItem(int i) {
        switch (i) {
            case SearchResults.INDEX_EXTERNAL_SEARCH:
                return setupExternalSearchListFragment();
            case SearchResults.INDEX_SEARCH_RESULTS:
                // fall-through, we are going to open the search results as default, too.
            default:
                return new SearchResultsFragment();
        }
    }

    private ExternalSearchListFragment setupExternalSearchListFragment() {
        ExternalSearchListFragment esf = new ExternalSearchListFragment();
        esf.setOnListItemClickListener(new ExternalSearchListFragment.OnListItemClickListener() {
            @Override
            public void onListItemClick(ListView l, View v, int position,
                                        long id, ExternalSearch externalSearch)
            {
                String searchUri = externalSearch.generateUri(
                        Utils.getSearchQuery(parent.getIntent())
                );

                Utils.debugLog(TAG, "Externally searching for: " + searchUri);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(searchUri));
                parent.startActivity(intent);
            }
        });

        return esf;
    }

    @Override
    public int getCount() { return SearchResults.INDEX_COUNT; }


    @Override
    public String getPageTitle(int i) {
        switch (i) {
            case SearchResults.INDEX_EXTERNAL_SEARCH:
                return parent.getString(R.string.search_tab_external_search);
            case SearchResults.INDEX_SEARCH_RESULTS:
                return parent.getString(R.string.search_tab_search_results);
            default:
                return "";
        }
    }
}
