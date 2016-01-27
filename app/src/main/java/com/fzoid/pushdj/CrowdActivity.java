package com.fzoid.pushdj;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationRequest;
import com.spotify.sdk.android.authentication.AuthenticationResponse;
import com.spotify.sdk.android.player.ConnectionStateCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import kaaes.spotify.webapi.android.models.Pager;
import kaaes.spotify.webapi.android.models.Playlist;
import kaaes.spotify.webapi.android.models.PlaylistSimple;
import kaaes.spotify.webapi.android.models.PlaylistTrack;
import kaaes.spotify.webapi.android.models.SnapshotId;
import kaaes.spotify.webapi.android.models.Track;
import kaaes.spotify.webapi.android.models.TrackToRemove;
import kaaes.spotify.webapi.android.models.TracksToRemove;
import kaaes.spotify.webapi.android.models.UserPrivate;
import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;

public class CrowdActivity extends AppCompatActivity implements
        ConnectionStateCallback, SongSearchDialog.SongSearchCallback {

    private static final String CLIENT_ID = "a53dd0dbe9c74340be7a6ebc40ed14af";
    private static final String REDIRECT_URI = "partify-crowd://callback";

    private TextView titleView, artistView, wisherView;

    private ListView upNextList;
    private UpNextListAdapter upNextAdapter;

    private SongSearchDialog ssd;

    @Override
    public void onLoggedIn() {
        Log.d("MainActivity", "User logged in");
    }

    @Override
    public void onLoggedOut() {
        Log.d("MainActivity", "User logged out");
    }

    @Override
    public void onLoginFailed(Throwable error) {
        Log.d("MainActivity", "Login failed");
    }

    @Override
    public void onTemporaryError() {
        Log.d("MainActivity", "Temporary error occurred");
    }

    @Override
    public void onConnectionMessage(String message) {
        Log.d("MainActivity", "Received connection message: " + message);
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);
        super.onDestroy();
    }

    private static final int REQUEST_CODE = 1337;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d("Crowd", "onCreate called!");

        // do initialization
        initUI();
        LocalBroadcastManager.getInstance(this).registerReceiver(
                mMessageReceiver, new IntentFilter(Comm.BROADCAST_ACTION));

        if (App.needsInitialization) {
            Comm.init(this);

            // initiate spotify login
            AuthenticationRequest.Builder builder = new AuthenticationRequest.Builder(
                    CLIENT_ID, AuthenticationResponse.Type.TOKEN, REDIRECT_URI);
            builder.setScopes(new String[]{
                    "playlist-modify-private",
                    "playlist-read-private",
                    "playlist-read-collaborative"
            });
            AuthenticationClient.openLoginActivity(this, REQUEST_CODE, builder.build());
            App.needsInitialization = false;
        } else {
            Comm.sendTcp(Message.update(App.userName));
        }
    }

    private void initUI() {
        setContentView(R.layout.activity_crowd);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        titleView = (TextView) findViewById(R.id.titleView);
        artistView = (TextView) findViewById(R.id.artistView);
        wisherView = (TextView) findViewById(R.id.wisherView);

        if (App.currentSong != null) {
            updateUI();
        }

        upNextAdapter = new UpNextListAdapter();
        upNextList = (ListView) findViewById(R.id.upNextList);
        upNextList.setAdapter(upNextAdapter);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                // Create and show the dialog.
                ssd = new SongSearchDialog();
                ssd.setContext(CrowdActivity.this);
                ssd.show(getFragmentManager(), "dialog");
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        // Check if result comes from the correct activity
        if (requestCode == REQUEST_CODE) {
            AuthenticationResponse response = AuthenticationClient.getResponse(resultCode, intent);
            Log.d("Main", "Log in error: " + response.getError());
            if (response.getType() == AuthenticationResponse.Type.TOKEN) {
                Comm.initializeApi(response.getAccessToken());
                initUser();
            }
        }
    }

    private void initUser() {
        Comm.spotify.getMe(new Callback<UserPrivate>() {
            @Override
            public void success(UserPrivate userPrivate, Response response) {
                App.userId = userPrivate.id;
                App.userName = userPrivate.display_name;
                if (App.userName == null) {
                    App.userName = App.userId;
                }
                Comm.sendTcp(Message.hello(App.userName));
                findFavouritesPlaylist();
            }

            @Override
            public void failure(RetrofitError error) {

            }
        });
    }

    private static final String PARTYFY_FAVOURITES = "Partify Favourites";

    private void findFavouritesPlaylist() {
        final int LIMIT = 20;
        final int[] offset = {0};
        final Map<String, Object> options = new HashMap<>();
        options.put("limit", LIMIT);
        options.put("offset", offset[0]);
        final boolean[] missing = {true};

        Callback<Pager<PlaylistSimple>> callback = new Callback<Pager<PlaylistSimple>>() {
            @Override
            public void success(Pager<PlaylistSimple> playlistSimplePager, Response response) {
                Log.d("Crowd", "Checking playlists starting from #" + offset[0]);
                Log.d("Crowd", "Total number of playlists: " + playlistSimplePager.total);
                for (PlaylistSimple p : playlistSimplePager.items) {
                    Log.d("Crowd", "Checking playlist " + p.name);
                    if (p.name.toLowerCase().equals(PARTYFY_FAVOURITES.toLowerCase())) {
                        Log.d("Crowd", "Found it!");
                        missing[0] = false;
                        Comm.spotify.getPlaylist(App.userId, p.id, new Callback<Playlist>() {
                            @Override
                            public void success(Playlist playlist, Response response) {
                                readFavourites(playlist);
                            }

                            @Override
                            public void failure(RetrofitError error) {

                            }
                        });
                        break;
                    }
                }

                if (missing[0]) {
                    if (offset[0] + LIMIT <= playlistSimplePager.total) {
                        // more to come, check next bunch and call this callback recursively
                        offset[0] += LIMIT;
                        options.put("offset", offset[0]);
                        Comm.spotify.getMyPlaylists(options, this);
                    } else {
                        createFavouritesPlaylist();
                    }
                }
            }

            @Override
            public void failure(RetrofitError error) {

            }
        };

        Comm.spotify.getMyPlaylists(options, callback);
    }

    private void readFavourites(Playlist playlist) {

        App.favouritesPlaylist = playlist;
        App.favourites.clear();
        for (PlaylistTrack t : App.favouritesPlaylist.tracks.items) {
            Wish fav = new Wish(
                    App.userName, t.track.uri, t.track.artists, t.track.name);
            fav.actualWish = false;
            App.favourites.add(fav);
        }

        int offset = 100;
        while (playlist.tracks.total > offset) {
            final Map<String, Object> options = new HashMap<>();
            options.put("limit", 100);
            options.put("offset", offset);
            Comm.spotify.getPlaylistTracks(
                    App.userName, playlist.id, options, new Callback<Pager<PlaylistTrack>>() {
                @Override
                public void success(Pager<PlaylistTrack> playlistTrackPager, Response response) {

                    for (PlaylistTrack t : playlistTrackPager.items) {
                        Wish fav = new Wish(
                                App.userName, t.track.uri, t.track.artists, t.track.name);
                        fav.actualWish = false;
                        App.favourites.add(fav);
                    }
                }

                @Override
                public void failure(RetrofitError error) {

                }
            });

            offset += 100;
        }
    }

    public void createFavouritesPlaylist() {
        final Map<String, Object> options = new HashMap<>();
        options.put("name", PARTYFY_FAVOURITES);
        options.put("public", false);
        Log.d("Crowd", "Creating new favouritesPlaylist playlist.");
        Comm.spotify.createPlaylist(App.userId, options, new Callback<Playlist>() {
            @Override
            public void success(Playlist playlist, Response response) {
                App.favouritesPlaylist = playlist;
            }

            @Override
            public void failure(RetrofitError error) {

            }
        });
    }

    public boolean isFavourite(Wish wish) {
        return App.favourites.contains(wish);
    }

    public void addFavourite(final Wish wish, final ImageButton favButton) {
        if (App.favourites.contains(wish)) {
            return;
        }

        Map<String, Object> options = new HashMap<>();
        options.put("uris", Collections.singletonList(wish.trackUri));
        Comm.spotify.addTracksToPlaylist(
                App.userId, App.favouritesPlaylist.id, new HashMap<String, Object>(), options,
            new Callback<Pager<PlaylistTrack>>() {
                @Override
                public void success(Pager<PlaylistTrack> playlistTrackPager, Response response) {
                    App.favourites.add(wish.asActual(false));
                    favButton.setImageResource(R.drawable.ic_star_filled);
                    upNextAdapter.notifyDataSetChanged();
                }

                @Override
                public void failure(RetrofitError error) {
                    Log.e("Crowd", "Failed to add favourite.", error);
                    Toast.makeText(
                        CrowdActivity.this, R.string.failed_to_sync_favs, Toast.LENGTH_LONG).show();
                }
            });
    }

    public void removeFavourite(final Wish wish, final ImageButton favButton) {
        if (!App.favourites.contains(wish)) {
            return;
        }

        TracksToRemove ttr = new TracksToRemove();
        TrackToRemove t = new TrackToRemove();
        t.uri = wish.trackUri;
        ttr.tracks = Collections.singletonList(t);
        Comm.spotify.removeTracksFromPlaylist(
                App.userId, App.favouritesPlaylist.id, ttr, new Callback<SnapshotId>() {
            @Override
            public void success(SnapshotId snapshotId, Response response) {
                App.favourites.remove(wish);
                favButton.setImageResource(R.drawable.ic_star_hollow);
                upNextAdapter.notifyDataSetChanged();
            }

            @Override
            public void failure(RetrofitError error) {
                Log.e("Crowd", "Failed to remove favourite.", error);
                Toast.makeText(
                    CrowdActivity.this, R.string.failed_to_sync_favs, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void updateUI() {
        // load track information
        artistView.setText(App.currentSong.artists);
        titleView.setText(App.currentSong.songName);
        wisherView.setText(getString(R.string.wished_by, App.currentSong.wisher));
    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            Message msg = Comm.parse(intent.getStringExtra(Comm.DATA));
            Log.d("Main", "Incoming message with kind " + msg.kind);
            if (msg.kind.equals("now-playing") ||
                    (msg.kind.equals("welcome") && msg.recipient.equals(App.userName))) {
                App.currentSong = msg.nowPlaying;
                App.wishes.remove(App.currentSong);
                upNextAdapter.notifyDataSetChanged();
                Comm.sendTcp(Message.wishList(App.userName, App.wishes));
                updateUI();
            } else if (msg.kind.equals("update")) {
                App.currentSong = msg.nowPlaying;
                App.wishes.removeAll(msg.played);
                upNextAdapter.notifyDataSetChanged();

            }
        }
    };

    public void chooseRandomWish() {
        if (App.favourites.isEmpty()) {
            return;
        }

        int randomIndex = new Random().nextInt(App.favourites.size());
        App.wishes.add(App.favourites.get(randomIndex));
    }

    @Override
    public void songSelected(Track song) {

        Wish newWish = new Wish(App.userName, song.uri, song.artists, song.name);

        if (!App.wishes.contains(newWish)) {
            App.wishes.add(newWish);
            Collections.sort(App.wishes);
            upNextAdapter.notifyDataSetChanged();
            ssd.dismiss();
            Comm.sendTcp(Message.wishList(App.userName, App.wishes));
        } else {
            int existingIndex = App.wishes.indexOf(newWish);
            if (!App.wishes.get(existingIndex).actualWish) {
                App.wishes.remove(existingIndex);
                App.wishes.add(newWish);
                Collections.sort(App.wishes);
                upNextAdapter.notifyDataSetChanged();
                ssd.dismiss();
                Comm.sendTcp(Message.wishList(App.userName, App.wishes));
            } else {
                Toast.makeText(this, R.string.already_in_list, Toast.LENGTH_LONG).show();
            }
        }
    }

    public void removeWish(Wish wish) {
        App.wishes.remove(wish);
        upNextAdapter.notifyDataSetChanged();
        Comm.sendTcp(Message.wishList(App.userName, App.wishes));
    }

    class UpNextListAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return App.wishes.size();
        }

        @Override
        public Object getItem(int position) {
            return App.wishes.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            View vi = convertView;
            if (vi == null) vi = getLayoutInflater().inflate(R.layout.wish_list_entry, null);

            final Wish w = App.wishes.get(position);
            ((TextView) vi.findViewById(R.id.title)).setText(w.songName);
            ((TextView) vi.findViewById(R.id.artist)).setText(w.artists);
            vi.findViewById(R.id.wisher).setVisibility(View.GONE);
            final ImageButton favButton = (ImageButton) vi.findViewById(R.id.fav_button);
            ImageButton removeButton = (ImageButton) vi.findViewById(R.id.remove_button);

            if (isFavourite(w)) {
                favButton.setImageResource(R.drawable.ic_star_filled);
                favButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        removeFavourite(w, favButton);
                    }
                });
            } else {
                favButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        addFavourite(w, favButton);
                    }
                });
            }

            removeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    removeWish(w);
                }
            });

            return vi;
        }
    }

    public static class App {

        public static boolean needsInitialization = true;

        public static Wish currentSong;
        public static String userName, userId;
        public static Playlist favouritesPlaylist;
        public static List<Wish> favourites = new ArrayList<>();
        public static List<Wish> wishes = new ArrayList<>();
    }
}