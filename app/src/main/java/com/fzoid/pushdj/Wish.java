package com.fzoid.pushdj;

import android.support.annotation.NonNull;

import java.util.List;

import kaaes.spotify.webapi.android.models.ArtistSimple;

public class Wish implements Comparable<Wish> {

    public static Wish THE_LAZY_SONG =
            new Wish("Partify Central", "spotify:track:386RUes7n1uM1yfzgeUuwp",
                "Bruno Mars", "The Lazy Song");
    public static Wish PULSE = new Wish(
            "Partify Central", "spotify:track:2EFr3U8KSpaw00v6J93tG1", "Ihsahn", "Pulse");

    public String wisher;
    public String trackUri;
    public String artists;
    public String songName;
    public boolean actualWish = true;

    public Wish(String wisher, String trackUri, String artists, String songName) {
        this.wisher = wisher;
        this.trackUri = trackUri;
        this.songName = songName;
        this.artists = artists;
    }

    public Wish(String wisher, String trackUri, List<ArtistSimple> artists, String songName) {
        this.wisher = wisher;
        this.trackUri = trackUri;
        this.songName = songName;

        String artistNames = "";
        for (ArtistSimple a : artists) {
            if (!artistNames.isEmpty()) artistNames += ", ";
            artistNames += a.name;
        }
        this.artists = artistNames;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Wish && ((Wish)o).trackUri.equals(trackUri);
    }

    public Wish asActual(boolean actual) {
        Wish newWish = new Wish(wisher, trackUri, artists, songName);
        newWish.actualWish = actual;
        return newWish;
    }

    @Override
    public int compareTo(@NonNull Wish another) {
        if (actualWish && !another.actualWish) {
            return -1;
        } else if (!actualWish && another.actualWish) {
            return 1;
        } else {
            return 0;
        }
    }
}
