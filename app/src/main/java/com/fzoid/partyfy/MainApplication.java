package com.fzoid.partyfy;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.spotify.sdk.android.player.ConnectionStateCallback;
import com.spotify.sdk.android.player.Player;
import com.spotify.sdk.android.player.PlayerNotificationCallback;
import com.spotify.sdk.android.player.PlayerState;
import com.spotify.sdk.android.player.Spotify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class MainApplication extends Application implements
        PlayerNotificationCallback, ConnectionStateCallback {

    public boolean needsInitialization = true;

    public Player player;
    public boolean paused = false;
    public Wish currentSong;
    public String trackUri = "";
    public long trackDuration = 1;
    public long trackPlayed = 1;
    public Thread seekBarUpdater;
    public Map<String, List<Wish>> wishes = new HashMap<>();
    public Map<String, List<Wish>> favourites = new HashMap<>();
    public List<Wish> played = new ArrayList<>();
    public List<Wish> upNext = new ArrayList<>();
    public List<String> lastWishesUsers = new ArrayList<>();

    private static final String TAG = "com.fzoid.partyfy.MainApplication";
    private PowerManager.WakeLock wakeLock = null;
    private WifiManager.WifiLock wifiLock = null;

    @Override
    public void onCreate() {
        super.onCreate();

        final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG);
        wifiLock.acquire();

        LocalBroadcastManager.getInstance(this).registerReceiver(
                messageReceiver, new IntentFilter(Comm.BROADCAST_ACTION));

        Comm.initCentral(this);
        //Comm.startInfiniteReceiver();
        Comm.startServer();
    }

    @Override
    public void onTerminate() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver);
        Spotify.destroyPlayer(this);
        super.onTerminate();
    }

    @Override
    public void onLoggedIn() {

    }

    @Override
    public void onLoggedOut() {

    }

    @Override
    public void onLoginFailed(Throwable throwable) {

    }

    @Override
    public void onTemporaryError() {

    }

    @Override
    public void onConnectionMessage(String s) {

    }

    @Override
    public void onPlaybackEvent(EventType eventType, PlayerState playerState) {

        switch (eventType) {
            case TRACK_CHANGED:
                trackUri = playerState.trackUri;
                // notify UI to update song display
                LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(UPDATE_SONG));
                break;
            case TRACK_END:
                nextTrack();
                break;
            case PAUSE:
                paused = true;
                LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(PAUSE));
                break;
            case PLAY:
                paused = false;
                LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(PLAY));
                break;
        }
    }

    @Override
    public void onPlaybackError(ErrorType errorType, String s) {

    }

    private BroadcastReceiver messageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            Message msg = Comm.parse(intent.getStringExtra(Comm.DATA));
            Log.d("Main", "Incoming message with kind " + msg.kind);
            if (msg.kind.equals("wish-list")) {
                wishes.put(msg.sender, msg.wishList);
                updateNext();
            } else if (msg.kind.equals("hello")) {
                Message resp = Message.nowPlaying(currentSong);
                resp.kind = "welcome";
                resp.recipient = msg.sender;
                Comm.send(resp);
            }
        }
    };

    public static final String UPDATE_LIST = "com.fzoid.partyfy.UPDATE_LIST";
    public static final String UPDATE_SONG = "com.fzoid.partyfy.UPDATE_SONG";
    public static final String PAUSE = "com.fzoid.partyfy.PAUSE";
    public static final String PLAY = "com.fzoid.partyfy.PLAY";

    public void nextTrack() {

        if (upNext.isEmpty()) {
            chooseFromFavourites();
        }

        currentSong = upNext.remove(0);
        played.add(currentSong);
        for (List<Wish> userWishes : wishes.values()) {
            userWishes.remove(currentSong);
        }
        updateNext();

        player.play(currentSong.trackUri);

        Comm.send(Message.nowPlaying(currentSong));

        lastWishesUsers.add(currentSong.wisher);
    }

    public void updateNext() {

        List<String> candidates = new ArrayList<>();
        for (int i = lastWishesUsers.size(); i > 0; i--) {
            String user = lastWishesUsers.get(i-1);
            if (!candidates.contains(user)) {
                candidates.add(0, user);
            }
        }

        for (String key : wishes.keySet()) {
            wishes.get(key).remove(currentSong);
            if (wishes.get(key).isEmpty()) {
                candidates.remove(key);
            } else if (!candidates.contains(key)) {
                candidates.add(0, key);
            }
        }

        upNext.clear();
        for (int i = 0; i < 3; i++) {
            for (String user : candidates) {
                if (wishes.get(user) != null && wishes.get(user).size() > i) {
                    upNext.add(wishes.get(user).get(i));
                }
            }
        }
        Collections.sort(upNext);
        // notify UI to update list
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(UPDATE_LIST));
    }

    public void chooseFromFavourites() {

        List<String> candidates = new ArrayList<>();
        for (int i = lastWishesUsers.size(); i > 0; i--) {
            String user = lastWishesUsers.get(i-1);
            if (
                favourites.containsKey(user) &&
                !favourites.get(user).isEmpty() &&
                !candidates.contains(user)
            ) {
                candidates.add(0, user);
            }
        }
        for (String user: favourites.keySet()) {
            if (!favourites.get(user).isEmpty() && !candidates.contains(user)) {
                candidates.add(0, user);
            }
        }

        for (String user : candidates) {
            List<Wish> favCandidates = new ArrayList<>();
            List<Wish> playedFavs = new ArrayList<>();
            for (Wish fav: favourites.get(user)) {
                if (played.contains(fav)) {
                    playedFavs.add(fav);
                } else {
                    favCandidates.add(fav);
                }
            }

            if (favCandidates.isEmpty()) {
                if (!playedFavs.isEmpty()) {
                    int randomNum = new Random().nextInt(playedFavs.size());
                    upNext.add(playedFavs.get(randomNum));
                }
            } else {
                int randomNum = new Random().nextInt(favCandidates.size());
                upNext.add(favCandidates.get(randomNum));
            }
        }

        if (upNext.isEmpty()) {
            upNext.add(Wish.THE_LAZY_SONG);
            return;
        }

        // notify UI to update list
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(UPDATE_LIST));
    }
}
