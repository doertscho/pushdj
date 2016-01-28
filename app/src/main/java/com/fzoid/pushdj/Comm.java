package com.fzoid.pushdj;

import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.StrictMode;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import kaaes.spotify.webapi.android.SpotifyApi;
import kaaes.spotify.webapi.android.SpotifyService;

public class Comm {

    public static String accessToken;
    public static SpotifyService spotify;

    public static void initializeApi(String token) {
        accessToken = token;
        SpotifyApi api = new SpotifyApi();
        api.setAccessToken(accessToken);
        spotify = api.getService();
    }

    static final int PORT = 24105;
    static final String BROADCAST_ACTION = "com.fzoid.partify.BROADCAST";
    static final String DATA = "com.fzoid.partify.DATA";

    public static Context ctx;
    public static MainApplication app;
    public static void initCentral(MainApplication app) {
        Comm.app = app;
        init(app);
    }
    public static void init(Context ctx) {
        Comm.ctx = ctx;
    }

    public static Gson gson = new GsonBuilder().create();
    public static Message parse(String data) {
        return gson.fromJson(data, Message.class);
    }

    public static void startServer() {
        new Thread(new TcpServer()).start();
    }

    static class TcpServer implements Runnable {

        @Override
        public void run() {
            try {
                // first, try to determine the internal IP address of the device running this
                // central instance
                List<NetworkInterface> interfaces = Collections.list(
                        NetworkInterface.getNetworkInterfaces());
                out: for (NetworkInterface intf: interfaces) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr: addrs) {
                        if (!addr.isLoopbackAddress() && !addr.getHostAddress().contains(":")) {
                            // we found it! let's keep it in mind
                            Log.d("TcpServer", "My IP address is " + addr.getHostAddress());
                            app.setIp(addr.getHostAddress());
                            break out;
                        }
                    }
                }
            } catch (Exception ex) {
                Log.e("TcpServer", "Failed to get own IP address.", ex);
            }

            ServerSocket server;
            try {
                server = new ServerSocket(PORT);
            } catch (IOException e) {
                Log.d("TcpServer", "Could not listen on port " + PORT);
                return;
            }


            while (true) {
                Socket client;
                try {
                    client = server.accept();
                    new Thread(new TcpWorker(client)).start();
                } catch (IOException e) {
                    Log.d("TcpServer", "Accept failed.");
                    return;
                }
            }
        }
    }

    static class TcpWorker implements Runnable {

        private Socket client;
        public TcpWorker(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {

            BufferedReader in;
            PrintWriter out;
            try {
                in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                out = new PrintWriter(client.getOutputStream(), true);
            } catch (IOException e) {
                Log.d("TcpServer", "Read failed.");
                return;
            }

            try {
                boolean printHttpHeaders = false;

                // read first line of incoming data to determine its type
                String data = in.readLine();
                if (data != null && !data.equals("")) {
                    Log.d("TcpWorker", "Received message starts with: " + data);

                    // is it an HTTP post request?
                    if (data.startsWith("POST")) {
                        int length = 0;
                        Log.d("TcpWorker", "Reading headers ...");

                        // read headers until a blank line is reached, which marks the end of the
                        // header section
                        while (!(data = in.readLine()).equals("")) {
                            Log.d("TcpWorker", "Read header line: " + data);
                            if (data.isEmpty()) {
                                break;
                            }

                            if (data.startsWith("Content-Length:")) {
                                length = Integer.parseInt(data.substring(15).trim());
                            }
                        }

                        char[] buf = new char[length];
                        Log.d("TcpWorker", "Reading body ...");
                        int read = in.read(buf, 0, length);
                        Log.d("TcpWorker", "Length: " + length + ", actually read: " + read);

                        // there might be less bytes to be read than stated by the header, due to
                        // non-ascii characters
                        // TODO: find a reliable way to read the correct number of bytes
                        data = new String(Arrays.copyOfRange(buf, 0, read));
                        Log.d("TcpWorker", "Read body: " + data);

                        // we have found an HTTP request, so we need to send an HTTP response
                        printHttpHeaders = true;
                    }

                    // if not, is it an OPTIONS request? angular uses these to check permissions
                    // before making an actual request
                    if (!printHttpHeaders && data.startsWith("OPTIONS")) {
                        Log.d("TcpWorker", "Sending OPTIONS response.");
                        out.println(
                            "HTTP/1.1 200 OK\n" +
                            "Server: Partify Central / v0.1\n" +
                            "Allow: POST, OPTIONS\n" +
                            "Access-Control-Allow-Headers: Content-Type\n" +
                            // the important line is this last one: it needs to contain the URL
                            // under which the web app is hosted
                            "Access-Control-Allow-Origin: http://www.doertsch.net"
                        );
                    }

                    // at this point, the data variable either still contains the first line if
                    // it was no HTTP request (which should be the only line then), or it contains
                    // all load data that came posted. either way, it should contain a JSON object
                    // encoding a [[Message]] instance.
                    else {
                        Message msg = Comm.parse(data);

                        // initialize the response to a "now playing" message, because that data
                        // is always interesting
                        Message resp = Message.nowPlaying(app.currentSong);
                        Log.d("Main", "Incoming message with kind " + msg.kind);
                        if (msg.kind.equals("wish-list")) {
                            // store whish list for this user and rebuild the list of upcoming
                            // tracks
                            app.wishes.put(msg.sender, msg.wishList);
                            app.updateNext();
                        } else if (msg.kind.equals("favourites")) {
                            // store the favourites for this user in case we need them when the
                            // playlist runs empty
                            app.favourites.put(msg.sender, msg.wishList);
                        } else if (msg.kind.equals("update")) {
                            // a (maybe newly connected) user asks for a status update. tell her
                            // which song is playing and which have already.
                            resp.kind = "update";
                            resp.recipient = msg.sender;
                            resp.played = app.played;
                        }

                        // TODO: find a reliable way to send the correct length value and correctly
                        // encoded data (see above)
                        String respData = gson.toJson(resp);
                        if (printHttpHeaders) {
                            out.println(
                                "HTTP/1.1 200 OK\n" +
                                "Server: Partify Central / v0.1\n" +
                                "Access-Control-Allow-Origin: http://www.doertsch.net\n" +
                                "Content-Type: application/json; charset=utf-8\n" +
                                "Content-Length: " + (respData.getBytes("UTF-8").length + 1) + "\n\n"
                            );
                        }
                        Log.d("TcpWorker", "Responding: " + respData);
                        out.println(respData);
                        out.println();
                    }
                    in.close();
                    out.close();
                }
            } catch (IOException e) {
                Log.e("TcpWorker", "Communication failed.", e);
            }

            try {
                client.close();
            } catch (IOException e) {
                Log.d("TcpWorker", "Failed to close socket.");
            }
        }
    }

    public static void sendTcp(Message message) {
        new TcpSendTask().execute(message);
    }

    static class TcpSendTask extends AsyncTask<Object, Void, Void> {

        static final String TAG = "TcpSender";

        @Override
        protected Void doInBackground(Object... params) {

            Socket client;
            BufferedReader in;
            PrintWriter out;
            try {
                client = new Socket("192.168.0.112", PORT);
                out = new PrintWriter(client.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            } catch(UnknownHostException e) {
                Log.e(TAG, "Failed to resolve host.", e);
                return null;
            } catch(IOException e) {
                Log.e(TAG, "I/O error occured.", e);
                return null;
            }

            String message = gson.toJson(params[0]);
            out.println(message);

            String data;
            try {
                if ((data = in.readLine()) != null) {
                    Log.d(TAG, "Received response: " + data);
                    // Send the packet data back to the UI thread
                    Intent localIntent = new Intent(BROADCAST_ACTION)
                            // Puts the data into the Intent
                            .putExtra(DATA, data);
                    // Broadcasts the Intent to receivers in this app.
                    LocalBroadcastManager.getInstance(ctx).sendBroadcast(localIntent);
                }
            } catch (IOException e) {
                Log.e(TAG, "Communication failed.", e);
            }

            try {
                client.close();
            } catch (IOException e) {
                Log.d(TAG, "Failed to close socket.");
            }

            return null;
        }
    }
}
