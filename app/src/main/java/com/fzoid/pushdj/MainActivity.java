package com.fzoid.pushdj;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
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
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationRequest;
import com.spotify.sdk.android.authentication.AuthenticationResponse;
import com.spotify.sdk.android.player.Config;
import com.spotify.sdk.android.player.Player;
import com.spotify.sdk.android.player.Spotify;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import kaaes.spotify.webapi.android.models.ArtistSimple;
import kaaes.spotify.webapi.android.models.Track;
import kaaes.spotify.webapi.android.models.UserPrivate;
import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;

public class MainActivity extends AppCompatActivity implements SongSearchDialog.SongSearchCallback {

    private static final String CLIENT_ID = "f6bca518db1a49c18b275380d1551659";
    private static final String REDIRECT_URI = "pushdj-central://callback";

    private TextView titleView, artistView, wisherView;
    private ImageButton playPauseButton, skipButton;
    private ImageView coverView;

    private SeekBar seekBar;

    private ListView upNextList;
    private UpNextListAdapter upNextAdapter;

    private SongSearchDialog ssd;

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver);
        super.onDestroy();
    }


    private static final int REQUEST_CODE = 1337;

    public MainApplication app() {
        return (MainApplication) getApplicationContext();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initUI();

        IntentFilter filter = new IntentFilter(MainApplication.UPDATE_LIST);
        filter.addAction(MainApplication.UPDATE_SONG);
        filter.addAction(MainApplication.PAUSE);
        filter.addAction(MainApplication.PLAY);
        LocalBroadcastManager.getInstance(this).registerReceiver(messageReceiver, filter);

        if (app().needsInitialization) {

            AuthenticationRequest.Builder builder = new AuthenticationRequest.Builder(
                    CLIENT_ID, AuthenticationResponse.Type.TOKEN, REDIRECT_URI);
            builder.setScopes(new String[]{"playlist-modify-private", "streaming"});
            AuthenticationRequest request = builder.build();

            Log.d("Main", "Trying to log in");
            AuthenticationClient.openLoginActivity(this, REQUEST_CODE, request);

            app().needsInitialization = false;
        } else {
            initializeButtons();
            initializeSeekBar();
        }
    }

    private void initUI() {
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        seekBar = (SeekBar) findViewById(R.id.seekBar);
        seekBar.setClickable(false);
        titleView = (TextView) findViewById(R.id.titleView);
        artistView = (TextView) findViewById(R.id.artistView);
        wisherView = (TextView) findViewById(R.id.wisherView);
        playPauseButton = (ImageButton) findViewById(R.id.playPauseButton);
        skipButton = (ImageButton) findViewById(R.id.skipButton);
        coverView = (ImageView) findViewById(R.id.coverView);

        upNextAdapter = new UpNextListAdapter();
        upNextList = (ListView) findViewById(R.id.upNextList);
        upNextList.setAdapter(upNextAdapter);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                // Create and show the dialog.
                ssd = new SongSearchDialog();
                ssd.setContext(MainActivity.this);
                ssd.show(getFragmentManager(), "dialog");
            }
        });

    }

    private BroadcastReceiver messageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(MainApplication.UPDATE_LIST)) {
                upNextAdapter.notifyDataSetChanged();
            } else if (intent.getAction().equals(MainApplication.UPDATE_SONG)) {
                updateUI();
            } else if (intent.getAction().equals(MainApplication.PAUSE)) {
                if (playPauseButton == null) return;
                playPauseButton.setImageResource(android.R.drawable.ic_media_play);
            } else if (intent.getAction().equals(MainApplication.PLAY)) {
                if (playPauseButton == null) return;
                playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
            }
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        Log.d("Main", "Received a response from log in.");
        // Check if result comes from the correct activity
        if (requestCode == REQUEST_CODE) {
            AuthenticationResponse response = AuthenticationClient.getResponse(resultCode, intent);
            Log.d("Main", "Log in error: " + response.getError());
            if (response.getType() == AuthenticationResponse.Type.TOKEN) {
                Comm.initializeApi(response.getAccessToken());
                Config playerConfig = new Config(this, Comm.accessToken, CLIENT_ID);
                Spotify.getPlayer(playerConfig, app(), new Player.InitializationObserver() {
                    @Override
                    public void onInitialized(Player player) {
                        app().player = player;
                        app().player.addConnectionStateCallback(app());
                        app().player.addPlayerNotificationCallback(app());
                        initializeButtons();
                        initializeSeekBar();
                        initializeUser();

                        app().nextTrack();
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        Log.e("MainActivity", "Could not initialize player", throwable);
                    }
                });
            }
        }
    }

    private void initializeButtons() {
        playPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (app().paused) {
                    app().player.resume();
                } else {
                    app().player.pause();
                }
            }
        });
        skipButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                app().player.skipToNext();
            }
        });
    }

    private void initializeSeekBar() {
        if (app().seekBarUpdater != null) {
            app().seekBarUpdater.interrupt();
        }

        app().seekBarUpdater = new Thread(new Runnable() {
            private Date lastCheck = new Date();
            @Override
            public void run() {
                try {
                    while (true) {
                        Thread.sleep(1000);
                        Date newDate = new Date();
                        long delta = newDate.getTime() - lastCheck.getTime();
                        lastCheck = newDate;
                        if (!app().paused) {
                            app().trackPlayed += delta;
                            final double perc =
                                    ((double) app().trackPlayed) / ((double) (app().trackDuration + 1));
                            seekBar.post(new Runnable() {
                                @Override
                                public void run() {
                                    int max = seekBar.getMax();
                                    seekBar.setProgress(
                                            Math.min(max, (int) (perc * (double) max)));
                                }
                            });
                        }
                    }
                } catch (InterruptedException e) {
                    // desired to stop this thread
                }
            }
        });
        app().seekBarUpdater.start();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    double perc = (double) progress / (double) seekBar.getMax();
                    app().trackPlayed = (long) (perc * (double) app().trackDuration);
                    app().player.seekToPosition((int) app().trackPlayed);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    public void initializeUser() {
        Comm.spotify.getMe(new Callback<UserPrivate>() {
            @Override
            public void success(UserPrivate userPrivate, Response response) {
                app().country = userPrivate.country;
            }

            @Override
            public void failure(RetrofitError error) {

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
        Comm.spotify.getTrack(app().trackUri.replaceAll("spotify:track:", ""), new Callback<Track>() {
            @Override
            public void success(Track track, Response response) {
                String artistNames = "";
                for (ArtistSimple a : track.artists) {
                    if (!artistNames.isEmpty()) artistNames += ", ";
                    artistNames += a.name;
                }
                artistView.setText(artistNames);
                titleView.setText(track.name);
                if (app().currentSong.actualWish) {
                    wisherView.setText(getString(R.string.wished_by, app().currentSong.wisher));
                } else {
                    wisherView.setText(getString(R.string.from_favs_of, app().currentSong.wisher));
                }

                if (!track.album.images.isEmpty()) {
                    new DownloadImageTask(track.album.images.get(0).url, new DlCallback<Bitmap>() {
                        @Override
                        public void dataAvailable(Bitmap data) {
                            coverView.setImageBitmap(data);
                        }
                    }).execute();
                }

                app().trackDuration = track.duration_ms;
                app().trackPlayed = 0;
            }

            @Override
            public void failure(RetrofitError error) {
                Log.e("MainActivity", "Failed to get track information: " + error.getMessage());
            }
        });
    }

    @Override
    public void songSelected(Track song) {

        Wish newWish = new Wish("Partify Central", song.uri, song.artists, song.name);

        List<Wish> centralWishes = app().wishes.get("Partify Central");
        if (centralWishes == null) centralWishes = new ArrayList<>();
        if (!centralWishes.contains(newWish)) {
            centralWishes.add(newWish);
            Collections.sort(centralWishes);
            app().wishes.put("Partify Central", centralWishes);
            app().updateNext();
            ssd.dismiss();
        } else {
            int existingIndex = app().upNext.indexOf(newWish);
            if (!app().upNext.get(existingIndex).actualWish) {
                app().upNext.remove(existingIndex);
                app().upNext.add(newWish);
                Collections.sort(app().upNext);
                upNextAdapter.notifyDataSetChanged();
                ssd.dismiss();
            } else {
                Toast.makeText(this, R.string.already_in_list, Toast.LENGTH_LONG).show();
            }
        }
    }


    interface DlCallback<T> {
        void dataAvailable(T data);
    }

    class DownloadImageTask extends AsyncTask<Void, Void, Bitmap> {

        private String url;
        private DlCallback<Bitmap> callback;

        public DownloadImageTask(String url, DlCallback<Bitmap> callback) {
            this.url = url;
            this.callback = callback;
        }

        @Override
        protected Bitmap doInBackground(Void... params) {

            Bitmap bitmap = null;
            try {
                URLConnection connection = new URL(url).openConnection();
                InputStream is = connection.getInputStream();
                bitmap = BitmapFactory.decodeStream(is);
                is.close();
            } catch (IOException e) {
                Log.e("DownloadImageTask", "Failed to load image.", e);
            }
            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            callback.dataAvailable(result);
        }
    }

    class UpNextListAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return app().upNext.size();
        }

        @Override
        public Object getItem(int position) {
            return app().upNext.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            View vi = convertView;
            if (vi == null) vi = getLayoutInflater().inflate(R.layout.wish_list_entry, null);

            Wish w = app().upNext.get(position);
            ((TextView) vi.findViewById(R.id.title)).setText(w.songName);
            ((TextView) vi.findViewById(R.id.artist)).setText(w.artists);
            TextView req = (TextView) vi.findViewById(R.id.wisher);
            if (w.actualWish) {
                req.setText(getString(R.string.wished_by, w.wisher));
            } else {
                req.setText(getString(R.string.from_favs_of, w.wisher));
            }
            vi.findViewById(R.id.fav_button).setVisibility(View.GONE);
            vi.findViewById(R.id.remove_button).setVisibility(View.GONE);

            return vi;
        }
    } /*

    class WaitForWishesTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Log.e("WFWTask", "Sleep interrupted.", e);
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (app().upNext.isEmpty()) {
                Log.d("WFWTask", "No one answered .. :(");
                Log.d("WFWTask", "Time to play The Lazy Song.");
                app().upNext.add(Wish.THE_LAZY_SONG);
            }
            nextTrack();
        }
    } */
}
