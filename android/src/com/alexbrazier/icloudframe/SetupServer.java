package com.alexbrazier.icloudframe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone setup page, served on the local network only while the QR code is on screen.
 * Every path starts with a random key from the QR code, so other devices on the network can't
 * find or use it. Phones that can't scan can type the address and the 6-digit PIN shown on the
 * frame instead, which hands them the key; five wrong PINs switch that off until next time.
 */
final class SetupServer implements Runnable {
    interface Listener {
        void onSaved();
        void onPinLocked();
    }

    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_CONNECTIONS = 8;
    private static final int MAX_HEADERS = 64;
    private static final long REQUEST_DEADLINE_MS = 15_000;
    private static final int MAX_PIN_TRIES = 5;
    // Easier to type than a random port; any free port if something else has it
    private static final int PREFERRED_PORT = 8080;
    private static SetupServer running;

    private final Context context;
    private final ServerSocket socket;
    private final String key, pin;
    private int pinTries;
    private final String address;
    private final ExecutorService workers = Executors.newFixedThreadPool(MAX_CONNECTIONS);
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile boolean stopped;

    private SetupServer(Context context) throws IOException {
        this.context = context.getApplicationContext();
        address = lanAddress();
        if (address == null) throw new IOException("Not connected to a network");
        ServerSocket s;
        try {
            s = new ServerSocket(PREFERRED_PORT);
        } catch (IOException e) {
            s = new ServerSocket(0);
        }
        socket = s;
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        key = sb.toString();
        pin = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    static synchronized SetupServer start(Context context, Listener listener) throws IOException {
        if (running == null) {
            running = new SetupServer(context);
            Thread t = new Thread(running, "setup-server");
            t.setDaemon(true);
            t.start();
            Log.i("Phone setup page open");
        }
        running.listener = listener;
        return running;
    }

    static synchronized void stop() {
        if (running == null) return;
        running.stopped = true;
        try {
            running.socket.close();
        } catch (IOException ignored) {
            // Closing anyway
        }
        running.workers.shutdownNow();
        running = null;
        Log.i("Phone setup page closed");
    }

    String url() {
        return base() + "/" + key + "/";
    }

    /** The address to type, without the key, for pairing with the PIN. */
    String base() {
        return "http://" + address + ":" + socket.getLocalPort();
    }

    /** The PIN, or null once it's been locked by wrong guesses. */
    synchronized String pin() {
        return pinTries >= MAX_PIN_TRIES ? null : pin;
    }

    /** The frame's IPv4 address on Wi-Fi (or Ethernet), which the phone must also be on. */
    private static String lanAddress() {
        String fallback = null;
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (nif.getName().startsWith("wlan")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
        } catch (IOException e) {
            Log.w("Couldn't read network addresses: " + e);
        }
        return fallback;
    }

    @Override
    public void run() {
        while (!stopped) {
            try {
                Socket client = socket.accept();
                // Refuse rather than queue, so slow or idle connections can't pile up
                if (inFlight.incrementAndGet() > MAX_CONNECTIONS) {
                    inFlight.decrementAndGet();
                    client.close();
                    continue;
                }
                workers.execute(() -> {
                    try {
                        handle(client);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            } catch (IOException e) {
                if (!stopped) Log.w("Setup server: " + e);
                return;
            } catch (java.util.concurrent.RejectedExecutionException e) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(5_000);
            long deadline = System.currentTimeMillis() + REQUEST_DEADLINE_MS;
            InputStream in = new java.io.BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in, deadline);
            if (requestLine == null) return;
            int contentLength = 0, headers = 0;
            String line;
            while ((line = readLine(in, deadline)) != null && !line.isEmpty()) {
                if (++headers > MAX_HEADERS) throw new IOException("Too many headers");
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            if (contentLength < 0 || contentLength > MAX_BODY) {
                respond(s, 413, "text/plain", "Too large");
                return;
            }
            byte[] body = new byte[contentLength];
            int off = 0, n;
            while (off < contentLength && (n = in.read(body, off, contentLength - off)) > 0) {
                off += n;
                if (System.currentTimeMillis() > deadline) throw new IOException("Request too slow");
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            route(s, parts[0], parts[1], new String(body, 0, off, StandardCharsets.UTF_8));
        } catch (IOException | NumberFormatException | JSONException e) {
            Log.w("Setup request failed: " + e);
        }
    }

    private void route(Socket s, String method, String path, String body) throws IOException, JSONException {
        String prefix = "/" + key;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.equals("/") && method.equals("GET")) {
            respond(s, 200, "text/html; charset=utf-8", raw(R.raw.pair));
            return;
        }
        if (path.equals("/pair") && method.equals("POST")) {
            pair(s, body);
            return;
        }
        // Without the key, every path looks like it doesn't exist. The key is compared in
        // constant time, so response timing doesn't reveal how much of a guess was right.
        int end = path.indexOf('/', 1);
        String given = path.startsWith("/") ? path.substring(1, end < 0 ? path.length() : end) : "";
        if (!MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            respond(s, 404, "text/plain", "Not found");
            return;
        }
        String rest = path.substring(prefix.length());
        if (rest.isEmpty()) {
            respondRaw(s, "HTTP/1.1 302 Found\r\nLocation: " + prefix + "/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        } else if (rest.equals("/") && method.equals("GET")) {
            respond(s, 200, "text/html; charset=utf-8", raw(R.raw.setup));
        } else if (rest.startsWith("/api/cover/") && method.equals("GET")) {
            cover(s, rest.substring("/api/cover/".length()));
        } else if (rest.equals("/api/settings") && method.equals("GET")) {
            respond(s, 200, "application/json", state().toString());
        } else if (rest.equals("/api/settings") && method.equals("POST")) {
            save(s, body);
        } else if (rest.equals("/api/albums") && method.equals("POST")) {
            albums(s, body);
        } else if (rest.equals("/api/sync") && method.equals("POST")) {
            FrameApp.get().sync().wake(true);
            respond(s, 200, "application/json", state().toString());
        } else {
            respond(s, 404, "text/plain", "Not found");
        }
    }

    private JSONObject state() throws JSONException {
        PhotoStore store = FrameApp.get().store();
        org.json.JSONArray albums = new org.json.JSONArray();
        for (Albums.Album a : Albums.list(context)) {
            albums.put(new JSONObject().put("id", a.id).put("on", a.on).put("name", store.name(a.id))
                    .put("count", store.count(a.id)).put("saved", store.savedCount(a.id))
                    .put("error", store.error(a.id)));
        }
        return SettingsSchema.describe(context)
                .put("albums", albums)
                .put("status", FrameApp.get().sync().status())
                .put("version", Updater.currentVersion(context));
    }

    /** {"add": link}, {"remove": id} or {"id": id, "on": true/false}. */
    private void albums(Socket s, String body) throws IOException, JSONException {
        JSONObject req;
        try {
            req = new JSONObject(body);
        } catch (JSONException e) {
            respond(s, 400, "application/json", new JSONObject().put("error", "Expected JSON").toString());
            return;
        }
        String error = null;
        if (req.has("add")) {
            error = Albums.add(context, req.optString("add"));
        } else if (req.has("remove")) {
            if (!Albums.remove(context, req.optString("remove"))) error = "No such album";
        } else if (req.has("id") && req.has("on")) {
            if (!Albums.setOn(context, req.optString("id"), req.optBoolean("on"))) error = "No such album";
        } else {
            error = "Expected add, remove or id and on";
        }
        if (error != null) {
            respond(s, 400, "application/json", new JSONObject().put("error", error).toString());
            return;
        }
        Log.i("Albums changed from phone");
        FrameApp.get().sync().wake(true);
        FrameApp.get().settingsChanged();
        Listener l = listener;
        if (l != null) main.post(l::onSaved);
        respond(s, 200, "application/json", state().toString());
    }

    private void save(Socket s, String body) throws IOException, JSONException {
        JSONObject changes;
        try {
            changes = new JSONObject(body);
        } catch (JSONException e) {
            respond(s, 400, "application/json", new JSONObject().put("error", "Expected JSON").toString());
            return;
        }
        SharedPreferences.Editor edit = new Prefs(context).raw().edit();
        StringBuilder errors = new StringBuilder();
        boolean reload = false;
        for (Iterator<String> it = changes.keys(); it.hasNext(); ) {
            String k = it.next();
            String error = SettingsSchema.put(context, edit, k, changes.get(k));
            if (error != null) errors.append(error).append('\n');
            if (k.equals("album") || k.equals("src_icloud") || k.equals("videos")) reload = true;
        }
        if (errors.length() > 0) {
            // Save nothing unless everything is valid
            respond(s, 400, "application/json", new JSONObject().put("error", errors.toString().trim()).toString());
            return;
        }
        edit.commit();
        Log.i("Settings saved from phone");
        FrameApp.get().sync().wake(reload);
        Schedule.update(context);
        FrameApp.get().settingsChanged();
        Listener l = listener;
        if (l != null) main.post(l::onSaved);
        respond(s, 200, "application/json", state().toString());
    }

    /** {"pin": "123456"}: answers with the page's address when it's right. */
    private void pair(Socket s, String body) throws IOException, JSONException {
        String given;
        try {
            given = new JSONObject(body).optString("pin").replaceAll("\\D", "");
        } catch (JSONException e) {
            given = "";
        }
        boolean ok, locked;
        synchronized (this) {
            locked = pinTries >= MAX_PIN_TRIES;
            ok = !locked && MessageDigest.isEqual(given.getBytes(StandardCharsets.US_ASCII), pin.getBytes(StandardCharsets.US_ASCII));
            if (!ok && !locked && ++pinTries >= MAX_PIN_TRIES) {
                locked = true;
                Log.w("Phone setup PIN locked after " + MAX_PIN_TRIES + " wrong tries");
                Listener l = listener;
                if (l != null) main.post(l::onPinLocked);
            }
        }
        if (ok) {
            respond(s, 200, "application/json", new JSONObject().put("url", "/" + key + "/").toString());
        } else {
            respond(s, 403, "application/json", new JSONObject().put("error", locked
                    ? "Too many wrong PINs. Scan the QR code on the frame instead."
                    : "That PIN isn't right. Check the frame and try again.").toString());
        }
    }

    /** A small JPEG of an album's newest photo. */
    private void cover(Socket s, String album) throws IOException {
        java.io.File file = Albums.allIds(context).contains(album) ? FrameApp.get().store().cover(album) : null;
        byte[] jpeg = file == null ? null : Images.thumbnail(file, 240);
        if (jpeg == null) {
            respond(s, 404, "text/plain", "No cover");
            return;
        }
        String head = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.length
                + "\r\nCache-Control: private, max-age=300\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(jpeg);
        out.flush();
    }

    private String raw(int id) throws IOException {
        try (InputStream in = context.getResources().openRawResource(id)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static void respond(Socket s, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 403 ? "Forbidden"
                : status == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + bytes.length
                + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff"
                + "\r\nConnection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static void respondRaw(Socket s, String response) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readLine(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 8192) throw new IOException("Header too long");
            if (System.currentTimeMillis() > deadline) throw new IOException("Request too slow");
        }
        if (c < 0 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }
}
