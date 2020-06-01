package org.jellyfin.androidtv.ui.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.TvApp;
import org.jellyfin.androidtv.preferences.ui.PreferencesActivity;
import org.jellyfin.androidtv.presentation.CardPresenter;
import org.jellyfin.androidtv.presentation.GridButtonPresenter;
import org.jellyfin.androidtv.startup.SelectUserActivity;
import org.jellyfin.androidtv.ui.GridButton;

public class HomeFragmentFooterRow extends HomeFragmentRow implements OnItemViewClickedListener {
    private static final int LOGOUT = 0;
    private static final int SETTINGS = 1;

    private Context context;

    public HomeFragmentFooterRow(Context context) {
        this.context = context;
    }

    @Override
    public void addToRowsAdapter(CardPresenter cardPresenter, ArrayObjectAdapter rowsAdapter) {
        HeaderItem header = new HeaderItem(rowsAdapter.size(), context.getString(R.string.lbl_settings));
        GridButtonPresenter presenter = new GridButtonPresenter();

        ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
        adapter.add(new GridButton(SETTINGS, context.getString(R.string.lbl_settings), R.drawable.tile_settings));
        adapter.add(new GridButton(LOGOUT, context.getString(R.string.lbl_logout), R.drawable.tile_logout));

        rowsAdapter.add(new ListRow(header, adapter));
    }

    public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item, RowPresenter.ViewHolder rowViewHolder, Row row) {
        if (!(item instanceof GridButton)) return;

        switch (((GridButton) item).getId()) {
            case LOGOUT:
                TvApp app = TvApp.getApplication();

                // Present user selection
                app.setLoginApiClient(app.getApiClient());

                // Open login activity
                Intent selectUserIntent = new Intent(context, SelectUserActivity.class);
                selectUserIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY); // Disallow back button
                context.startActivity(selectUserIntent);

                ((Activity) context).finish();

                break;
            case SETTINGS:
                Intent settingsIntent = new Intent(context, PreferencesActivity.class);
                context.startActivity(settingsIntent);
                break;
        }

    }
}
