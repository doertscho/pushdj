package com.fzoid.partyfy;

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

    private EditText input;
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
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.song_search_dialog, container, false);

        ListView rv = (ListView) v.findViewById(R.id.results);
        final ResultsAdapter ra = new ResultsAdapter();
        rv.setAdapter(ra);
        rv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                callback.songSelected(results.get(position));
            }
        });

        input = (EditText) v.findViewById(R.id.input);

        input.addTextChangedListener(new TextWatcher() {

            private int i = 0;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
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

                    }
                });
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        return v;
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
            String artistNames = "";
            for (ArtistSimple a : t.artists) {
                if (!artistNames.isEmpty()) artistNames += ", ";
                artistNames += a.name;
            }
            ((TextView) vi.findViewById(R.id.title)).setText(t.name);
            ((TextView) vi.findViewById(R.id.artist)).setText(artistNames);
            vi.findViewById(R.id.wisher).setVisibility(View.GONE);
            vi.findViewById(R.id.fav_button).setVisibility(View.GONE);
            vi.findViewById(R.id.remove_button).setVisibility(View.GONE);

            return vi;
        }
    }
}