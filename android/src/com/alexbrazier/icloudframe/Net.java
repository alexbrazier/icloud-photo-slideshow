package com.alexbrazier.icloudframe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * HTTP helpers. HTTPS trusts the system's roots plus the roots bundled in res/raw/roots.pem
 * (Apple, DigiCert, GitHub's and MET Norway's), so old frames whose own root list predates a
 * server's certificate still connect, and an app update can bring new roots.
 */
final class Net {
    static final String USER_AGENT =
            "icloud-frame/" + BuildInfo.VERSION_NAME + " (+https://github.com/alexbrazier/icloud-photo-slideshow)";

    private static SSLSocketFactory socketFactory;

    static synchronized void init(Context context) {
        if (socketFactory != null) return;
        try {
            List<X509TrustManager> managers = new ArrayList<>();
            managers.add(trustManager(null));
            KeyStore bundled = KeyStore.getInstance(KeyStore.getDefaultType());
            bundled.load(null, null);
            try (InputStream in = context.getResources().openRawResource(R.raw.roots)) {
                int i = 0;
                for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    bundled.setCertificateEntry("root" + i++, cert);
                }
            }
            managers.add(trustManager(bundled));
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] {new EitherTrustManager(managers)}, null);
            socketFactory = ssl.getSocketFactory();
        } catch (Exception e) {
            // Fall back to the system's defaults rather than failing outright
            socketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        }
    }

    private static X509TrustManager trustManager(KeyStore store) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("No X509TrustManager");
    }

    /** Accepts a server chain that any of the managers accepts. */
    private static final class EitherTrustManager implements X509TrustManager {
        private final List<X509TrustManager> managers;

        EitherTrustManager(List<X509TrustManager> managers) { this.managers = managers; }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager tm : managers) {
                try {
                    tm.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException e) {
                    last = e;
                }
            }
            throw last != null ? last : new CertificateException("Untrusted server");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Client certificates are not used");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509TrustManager tm : managers) {
                for (X509Certificate c : tm.getAcceptedIssuers()) all.add(c);
            }
            return all.toArray(new X509Certificate[0]);
        }
    }

    static final class Response {
        final int status;
        final byte[] body;

        Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (conn instanceof HttpsURLConnection && socketFactory != null) {
            ((HttpsURLConnection) conn).setSSLSocketFactory(socketFactory);
        }
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        return conn;
    }

    static Response get(String url, int maxBytes) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            return read(conn, maxBytes);
        } finally {
            conn.disconnect();
        }
    }

    static Response postJson(String url, String json, int maxBytes) throws IOException {
        HttpURLConnection conn = post(url, json);
        try {
            return read(conn, maxBytes);
        } finally {
            conn.disconnect();
        }
    }

    /** Sends a JSON POST and returns the connection, for callers that stream the reply. */
    static HttpURLConnection post(String url, String json) throws IOException {
        HttpURLConnection conn = open(url);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Origin", "https://www.icloud.com");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }
        return conn;
    }

    /** A reply body that fails once more than maxBytes have been read. */
    static InputStream limited(InputStream in, long maxBytes) {
        return new java.io.FilterInputStream(in) {
            private long total;

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0 && ++total > maxBytes) throw new IOException("Response too large");
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                int n = super.read(buf, off, len);
                if (n > 0 && (total += n) > maxBytes) throw new IOException("Response too large");
                return n;
            }
        };
    }

    /**
     * Downloads to a file, failing past maxBytes. GitHub release assets need redirects followed;
     * iCloud downloads don't, so they can't be sent anywhere other than the checked host.
     */
    static void download(String url, File dest, long maxBytes, boolean followRedirects) throws IOException {
        HttpURLConnection conn = open(url);
        conn.setInstanceFollowRedirects(followRedirects);
        try {
            int status = conn.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status + " for " + host(url));
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) throw new IOException("Download too large");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    static Response read(HttpURLConnection conn, int maxBytes) throws IOException {
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return new Response(status, new byte[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        try (InputStream stream = in) {
            int n;
            while ((n = stream.read(buf)) > 0) {
                if (out.size() + n > maxBytes) throw new IOException("Response too large");
                out.write(buf, 0, n);
            }
        }
        return new Response(status, out.toByteArray());
    }

    static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (IOException e) {
            return "?";
        }
    }

    @SuppressWarnings("deprecation")
    static boolean online(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }
}
