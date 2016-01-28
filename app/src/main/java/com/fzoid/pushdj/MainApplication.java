package com.fzoid.pushdj;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class MainApplication extends Application implements
        PlayerNotificationCallback, ConnectionStateCallback {

    public boolean needsInitialization = true;

    public String market = "HINTERBIKI";
    public String accessCode = "";
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

    private static final String TAG = "com.fzoid.partify.MainApplication";
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

        Comm.initCentral(this);
        Comm.startServer();
    }

    @Override
    public void onTerminate() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        Spotify.destroyPlayer(this);
        super.onTerminate();
    }

    static final Integer[] frequentBytes = {   192, 168,   0, 112,  10, 172,  16, 100, 101, 102 };
    static final Character[] frequentChars = { 'm', 't', 'a', 'l', 'x', 'b', 'g', 'c', 'u', 'r' };
    static final Character[] otherChars =
        { 'd', 'e', 'f', 'h', 'i', 'j', 'k', 'n', 'o', 'p', 'q', 's', 'v', 'w', 'y', 'z' };
    static final List<Integer> frequentBytesList = Arrays.asList(frequentBytes);

    public void setIp(String ip) {
        // encode ip address into access code
        String[] parts = ip.split(".");
        if (parts.length != 4) {
            Log.d("MainApp", "Received invalid ip address: " + ip);
            return;
        }

        String code = "";
        Integer b, index;
        for (String part: parts) {
            b = Integer.parseInt(part);

            // here's how the mapping works:
            // ten letters are available for frequent numbers to be encoded in a single character;
            // the remaining 16 letters are used to encode any byte value in two characters.
            if ((index = frequentBytesList.indexOf(b)) != -1) {
                code += frequentChars[index];
            } else {
                Integer hi = b / 16;
                code += otherChars[hi];
                Integer lo = b % 16;
                code += otherChars[lo];
            }
        }

        accessCode = code;
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
                // notify UI to update appearance of the play/pause button
                LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(PAUSE));
                break;
            case PLAY:
                paused = false;
                // notify UI to update appearance of the play/pause button
                LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(PLAY));
                break;
        }
    }

    @Override
    public void onPlaybackError(ErrorType errorType, String s) {

    }

    public static final String UPDATE_LIST = "com.fzoid.partify.UPDATE_LIST";
    public static final String UPDATE_SONG = "com.fzoid.partify.UPDATE_SONG";
    public static final String PAUSE = "com.fzoid.partify.PAUSE";
    public static final String PLAY = "com.fzoid.partify.PLAY";

    public void nextTrack() {

        if (upNext.isEmpty()) {
            chooseFromFavourites();
        }

        currentSong = upNext.remove(0);

        // remember this one in the history log
        played.add(currentSong);
        lastWishesUsers.add(currentSong.wisher);

        // remove this song from the list of any user that requested it
        for (List<Wish> userWishes : wishes.values()) {
            userWishes.remove(currentSong);
        }

        updateNext();

        // start the playback, finally!
        player.play(currentSong.trackUri);
    }

    public void updateNext() {

        // first, gather a list of all users that are known to the system in any way
        // these may be users that sent song requests ...
        List<String> candidates = new ArrayList<>();
        for (int i = lastWishesUsers.size(); i > 0; i--) {
            String user = lastWishesUsers.get(i-1);
            if (!candidates.contains(user)) {
                // the earlier a user appears in the "recently placed" list, the later a wish by
                // this user should be chosen again, so this list grows to the left
                candidates.add(0, user);
            }
        }
        // ... and also users that only sent some favourite songs
        for (String key : wishes.keySet()) {
            wishes.get(key).remove(currentSong);
            if (wishes.get(key).isEmpty()) {
                candidates.remove(key);
            } else if (!candidates.contains(key)) {
                candidates.add(0, key);
            }
        }

        // make a fresh start ...
        upNext.clear();
        // ... and cyclically chose the most current wishes, in the order that was just determined.
        // add up to three wishes by each user to the queue.
        for (int i = 0; i < 3; i++) {
            for (String user : candidates) {
                if (wishes.get(user) != null && wishes.get(user).size() > i) {
                    upNext.add(wishes.get(user).get(i));
                }
            }
        }

        // sort the list such that actual requests are preferred over favourite selections
        Collections.sort(upNext);

        // notify UI to update list
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(UPDATE_LIST));
    }

    public void chooseFromFavourites() {

        // again, build a list of qualifying users first
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

        // for each user, add at most one favourite song to the playlist
        for (String user : candidates) {
            // first, determine whether there are favourite songs that have not yet been played
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
                    // if there are none, choose any favourite song from this user's list
                    int randomNum = new Random().nextInt(playedFavs.size());
                    upNext.add(playedFavs.get(randomNum));
                }
            } else {
                // otherwise, choose a favourite song that we haven't heard tonight
                int randomNum = new Random().nextInt(favCandidates.size());
                upNext.add(favCandidates.get(randomNum));
            }
        }

        // hopefully, at least some songs have been added at this point, otherwise ...
        if (upNext.isEmpty()) {
            // oh no ...
            upNext.add(Wish.THE_LAZY_SONG);
        }

        // notify UI to update list
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(UPDATE_LIST));
    }
}
