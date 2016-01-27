package com.fzoid.pushdj;

import java.util.List;

public class Message {

    public static int NEXT_ID = 1;
    public int id = 0;

    public String kind = "";
    public String sender = "";
    public String recipient = "";

    public List<Wish> wishList;
    public List<Wish> played;
    public Wish nowPlaying;

    public Message() {
        id = NEXT_ID;
        NEXT_ID++;
    }

    static Message nowPlaying(Wish song) {
        Message msg = new Message();
        msg.kind = "now-playing";
        msg.sender = "Partify Central";
        msg.nowPlaying = song;
        return msg;
    }

    static Message hello(String userName) {
        Message msg = new Message();
        msg.kind = "hello";
        msg.sender = userName;
        return msg;
    }

    static Message update(String userName) {
        Message msg = new Message();
        msg.kind = "update";
        msg.sender = userName;
        return msg;
    }

    static Message wishList(String userName, List<Wish> wishes) {
        Message msg = new Message();
        msg.kind = "wish-list";
        msg.sender = userName;
        msg.wishList = wishes;
        return msg;
    }

    static Message needWishes() {
        Message msg = new Message();
        msg.kind = "need-wishes";
        return msg;
    }
}
