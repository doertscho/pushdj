package com.fzoid.pushdj;

import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.StrictMode;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.util.SparseArray;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

        try {
            broadcastAddress = getBroadcastAddress();
        } catch (IOException e) {
            Log.e("Comm", "Failed to get broadcast address.", e);
        }
    }

    public static void startInfiniteReceiver() {
        new Thread(new InfiniteBroadcastReceiver()).start();
        new Thread(new Talker()).start();
    }

    public static void startServer() {
        new Thread(new TcpServer()).start();
        //new Thread(new Talker()).start();
    }

    public static InetAddress broadcastAddress;
    public static Gson gson = new GsonBuilder().create();

    public static final StrictMode.ThreadPolicy THREAD_POLICY =
            new StrictMode.ThreadPolicy.Builder().permitAll().build();

    public static Message parse(String data) {
        return gson.fromJson(data, Message.class);
    }

    public static InetAddress getBroadcastAddress() throws IOException {
        WifiManager wifi = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            throw new IOException("Failed to retrieve WifiManager.");
        }
        DhcpInfo dhcp = wifi.getDhcpInfo();
        if (dhcp == null) {
            throw new IOException("Failed to retrieve DHCP info.");
        }

        int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
        byte[] quads = new byte[4];
        for (int k = 0; k < 4; k++)
            quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
        return InetAddress.getByAddress(quads);
    }

    static class Talker implements Runnable {

        @Override
        public void run() {
            try {
                while (true) {
                    Log.d("Talker", "I'm alive!");
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Log.d("Talker", "Talker interrupted!");
            }
        }
    }

    static class InfiniteBroadcastReceiver implements Runnable {

        static final String TAG = "InfBroadcastReceiver";

        @Override
        public void run() {
            Log.i(TAG, "InfiniteBroadcastReceiver starting.");
            DatagramSocket socket;
            try {
                // Keep a socket open to listen to all UDP traffic that is destined for this port
                socket = new DatagramSocket(PORT, InetAddress.getByName("0.0.0.0"));
                socket.setBroadcast(true);

                while (true) {
                    // Receive a packet
                    byte[] recvBuf = new byte[15000];
                    DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                    socket.receive(packet);

                    // Packet received
                    String data = new String(packet.getData()).trim();
                    Log.i(TAG, "Received message: " + data);

                    // Send the packet data back to the UI thread
                    Intent localIntent = new Intent(BROADCAST_ACTION)
                            // Puts the data into the Intent
                            .putExtra(DATA, data);
                    // Broadcasts the Intent to receivers in this app.
                    LocalBroadcastManager.getInstance(ctx).sendBroadcast(localIntent);
                }
            } catch (IOException e) {
                Log.e(TAG, "Receiver crashed.", e);
            }
        }
    }

    static class TcpServer implements Runnable {

        @Override
        public void run() {
            try {
                List<NetworkInterface> interfaces = Collections.list(
                        NetworkInterface.getNetworkInterfaces());
                out: for (NetworkInterface intf: interfaces) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr: addrs) {
                        if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                            // is an IPv4 address
                            Log.d("TcpServer", "My IP address is " + addr.getHostAddress());
                            break out;
                        }
                    }
                }
            } catch (Exception ex) {
                Log.e("TcpServer", "Failed to get IP address.", ex);
            }

            ServerSocket server;
            try {
                server = new ServerSocket(24105);
            } catch (IOException e) {
                Log.d("TcpServer", "Could not listen on port 24105");
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
                String data = in.readLine();
                if (data != null && !data.equals("")) {
                    Log.d("TcpWorker", "Received message: " + data);

                    if (data.startsWith("POST")) {
                        Log.d("TcpWorker", "Reading headers ...");
                        int length = 0;
                        while (!(data = in.readLine()).equals("")) {
                            Log.d("TcpWorker", "Read header line: " + data);
                            if (data.isEmpty()) {
                                break;
                            }

                            if (data.startsWith("Content-Length:")) {
                                length = Integer.parseInt(data.substring(15).trim());
                            }
                        }
                        Log.d("TcpWorker", "Reading body ...");
                        char[] buf = new char[length];
                        int read = in.read(buf, 0, length);
                        Log.d("TcpWorker", "Length: " + length + ", actually read: " + read);
                        // cut off extra bytes caused by non-ascii chars
                        data = new String(Arrays.copyOfRange(buf, 0, read));
                        Log.d("TcpWorker", "Read body: " + data);
                        printHttpHeaders = true;
                    }

                    if (!printHttpHeaders && data.startsWith("OPTIONS")) {
                        Log.d("TcpWorker", "Sending OPTIONS response.");
                        out.println(
                            "HTTP/1.1 200 OK\n" +
                            "Server: Partify Central / v0.1\n" +
                            "Allow: POST, OPTIONS\n" +
                            "Access-Control-Allow-Headers: Content-Type\n" +
                            "Access-Control-Allow-Origin: http://www.doertsch.net"
                        );
                    } else {
                        Message msg = Comm.parse(data);
                        Message resp = Message.nowPlaying(app.currentSong);
                        Log.d("Main", "Incoming message with kind " + msg.kind);
                        if (msg.kind.equals("wish-list")) {
                            app.wishes.put(msg.sender, msg.wishList);
                            app.updateNext();
                        } else if (msg.kind.equals("favourites")) {
                            app.favourites.put(msg.sender, msg.wishList);
                        } else if (msg.kind.equals("hello")) {
                            resp.kind = "welcome";
                            resp.recipient = msg.sender;
                        } else if (msg.kind.equals("update")) {
                            resp.kind = "update";
                            resp.recipient = msg.sender;
                            resp.played = app.played;
                        }

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

    public static void send(Message message) {
        new BroadcastSendTask().execute(message);
    }

    public static void sendTcp(Message message) {
        new TcpSendTask().execute(message);
    }

    static class BroadcastSendTask extends AsyncTask<Object, Void, Void> {

        static final String TAG = "BroadcastSender";

        @Override
        protected Void doInBackground(Object... params) {

            StrictMode.setThreadPolicy(THREAD_POLICY);

            try {
                // open a random port to send the package
                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);
                String message = gson.toJson(params[0]);
                byte[] sendData = message.getBytes();
                DatagramPacket sendPacket = new DatagramPacket(
                        sendData, sendData.length, broadcastAddress, PORT);
                socket.send(sendPacket);
                String data = new String(sendPacket.getData()).trim();
                Log.i(TAG, "Sent message: " + data);
            } catch (IOException e) {
                Log.e(TAG, "Sender crashed.", e);
            }

            return null;
        }
    }

    static class TcpSendTask extends AsyncTask<Object, Void, Void> {

        static final String TAG = "TcpSender";

        @Override
        protected Void doInBackground(Object... params) {

            StrictMode.setThreadPolicy(THREAD_POLICY);

            Socket client;
            BufferedReader in;
            PrintWriter out;
            try {
                client = new Socket("192.168.0.112", 24105);
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

    public static final SparseArray<String> awaitedMessages = new SparseArray<>();
    public static Thread singleReceiverThread;
    public static int NEXT_MESSAGE_ID = 0;
    private static final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor();

    public static void await(String message) {
        NEXT_MESSAGE_ID++;
        final int id = NEXT_MESSAGE_ID;
        synchronized (awaitedMessages) {
            awaitedMessages.put(id, message);
        }

        if (
            singleReceiverThread == null ||
            singleReceiverThread.isInterrupted() ||
            !singleReceiverThread.isAlive()
        ) {
            singleReceiverThread = new Thread(new SingleBroadcastReceiver());
            singleReceiverThread.start();
        }

        worker.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (awaitedMessages) {
                    if (awaitedMessages.get(id) == null) return;

                    awaitedMessages.remove(id);

                    if (
                        awaitedMessages.size() > 0 ||
                        singleReceiverThread == null ||
                        singleReceiverThread.isInterrupted()
                    ) {
                        return;
                    }
                }

                singleReceiverThread.interrupt();
            }
        }, 3, TimeUnit.SECONDS);
    }

    static class SingleBroadcastReceiver implements Runnable {

        static final String TAG = "SingleBroadcastReceiver";

        @Override
        public void run() {
            Log.i(TAG, "SingleBroadcastReceiver starting.");
            try {
                // Keep a socket open to listen to all UDP traffic that is destined for this port
                DatagramSocket socket = new DatagramSocket(PORT, InetAddress.getByName("0.0.0.0"));
                socket.setBroadcast(true);

                int awaiting;
                synchronized (awaitedMessages) {
                    awaiting = awaitedMessages.size();
                }
                while (awaiting > 0) {

                    // Receive a packet
                    byte[] recvBuf = new byte[15000];
                    DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                    socket.receive(packet);

                    // Packet received
                    String data = new String(packet.getData()).trim();
                    Log.i(TAG, "Received message: " + data);

                    String kind = Comm.parse(data).kind;

                    // Send the packet data back to the UI thread
                    Intent localIntent = new Intent(BROADCAST_ACTION)
                            // Puts the data into the Intent
                            .putExtra(DATA, data);
                    // Broadcasts the Intent to receivers in this app.
                    LocalBroadcastManager.getInstance(ctx).sendBroadcast(localIntent);

                    // remove all matching entries from the awaited list
                    synchronized (awaitedMessages) {
                        List<Integer> toBeRemoved = new ArrayList<>();
                        for (int i = 0; i < awaitedMessages.size(); i++) {
                            if (awaitedMessages.valueAt(i).equals(kind)) {
                                toBeRemoved.add(0, i);
                            }
                        }

                        for (int i: toBeRemoved) {
                            awaitedMessages.removeAt(i);
                        }

                        awaiting = awaitedMessages.size();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Receiver crashed.", e);
            }
        }
    }
}
