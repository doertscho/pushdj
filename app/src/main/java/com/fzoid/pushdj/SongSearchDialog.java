package com.fzoid.pushdj;

import android.app.Activity;
import android.app.DialogFragment;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import kaaes.spotify.webapi.android.models.ArtistSimple;
import kaaes.spotify.webapi.android.models.Track;
import kaaes.spotify.webapi.android.models.TracksPager;
import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;

public class SongSearchDialog extends DialogFragment {

    interface SongSearchCallback {
        void songSelected(Track song);
    }

    private List<Track> results = new ArrayList<>();

    private Activity ctx;
    private SongSearchCallback callback;
    public void setContext(Activity ctx) {
        this.ctx = ctx;

        if (ctx instanceof SongSearchCallback) {
            callback = (SongSearchCallback) ctx;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.song_search_dialog, container, false);

        ListView rv = (ListView) v.findViewById(R.id.results);
        final ResultsAdapter ra = new ResultsAdapter();
        rv.setAdapter(ra);
        rv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Track track = results.get(position);
                // as clicks can not be prevented, check again whether this song may be played
                if (track.album.available_markets.contains(app().market)) {
                    // if so, notify the application
                    callback.songSelected(results.get(position));
                }
            }
        });

        EditText input = (EditText) v.findViewById(R.id.input);

        // whenever the entered keywords change, a request to the Spotify api is to be kicked off
        input.addTextChangedListener(new TextWatcher() {

            private int i = 0;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

                // remember the number of this request, so that in case some request takes longer
                // than a following one, it does not override the more accurate results
                i++;
                final int current = i;
                Comm.spotify.searchTracks(s.toString(), new Callback<TracksPager>() {
                    @Override
                    public void success(TracksPager tracksPager, Response response) {
                        if (i == current) {
                            results = tracksPager.tracks.items;
                            ra.notifyDataSetChanged();
                        }
                    }

                    @Override
                    public void failure(RetrofitError error) {
                        // TODO: we should probably add some handling here, some day
                    }
                });
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });

        return v;
    }

    public MainApplication app() {
        return (MainApplication) ctx.getApplicationContext();
    }

    class ResultsAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return results.size();
        }

        @Override
        public Object getItem(int position) {
            return results.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            View vi = convertView;
            if (vi == null) vi = ctx.getLayoutInflater().inflate(R.layout.wish_list_entry, null);

            Track t = results.get(position);

            // build list of artist names
            String artistNames = "";
            for (ArtistSimple a : t.artists) {
                if (!artistNames.isEmpty()) artistNames += ", ";
                artistNames += a.name;
            }

            // populate, or hide, UI elements
            ((TextView) vi.findViewById(R.id.title)).setText(t.name);
            ((TextView) vi.findViewById(R.id.artist)).setText(artistNames);
            vi.findViewById(R.id.fav_button).setVisibility(View.GONE);
            vi.findViewById(R.id.remove_button).setVisibility(View.GONE);

            // check whether this song may be played by the current Spotify user
            if (t.album.available_markets.contains(app().market)) {
                vi.findViewById(R.id.wisher).setVisibility(View.GONE);
            } else {
                // if not, show a warning
                TextView extra = (TextView) vi.findViewById(R.id.wisher);
                extra.setVisibility(View.VISIBLE);
                extra.setText(R.string.licence_not_available);
                extra.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            }

            return vi;
        }
    }
}