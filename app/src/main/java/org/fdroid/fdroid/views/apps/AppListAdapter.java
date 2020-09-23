package org.fdroid.fdroid.views.apps;

import android.app.Activity;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.fdroid.fdroid.R;
import org.fdroid.fdroid.data.App;

import java.util.ArrayList;

class AppListAdapter extends RecyclerView.Adapter<StandardAppListItemController> {

    private ArrayList<App> apps;
    private final Activity activity;
    private final AppListItemDivider divider;

    AppListAdapter(Activity activity) {
        this.activity = activity;
        divider = new AppListItemDivider(activity);
        setHasStableIds(true);
    }

    public void setApps(ArrayList<App> apps) {
        this.apps = apps;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public StandardAppListItemController onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new StandardAppListItemController(activity, activity.getLayoutInflater().inflate(R.layout.app_list_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull StandardAppListItemController holder, int position) {
        holder.bindModel(apps.get(position));
    }

    @Override
    public long getItemId(int position) {
        return apps.get(position).getId();
    }

    @Override
    public int getItemCount() {
        return apps == null ? 0 : apps.size();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        recyclerView.addItemDecoration(divider);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        recyclerView.removeItemDecoration(divider);
        super.onDetachedFromRecyclerView(recyclerView);
    }
}
