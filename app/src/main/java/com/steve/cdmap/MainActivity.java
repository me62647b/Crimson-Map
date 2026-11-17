package com.steve.cdmap;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.widget.Toast;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Crimson Desert Map – Android app.
 * Shows the bundled map in a WebView and gives it a small native bridge (window.CDApp)
 * for Wi-Fi sync with the Windows app: finding the PC, sending sync requests,
 * and copying the HD map tiles from the PC.
 */
public class MainActivity extends Activity {

    private static final int UDP_PORT = 47801;
    private static final int HTTP_PORT = 47800;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";

    private static final int REQ_OPEN_FILE = 101;
    private static final int REQ_SAVE_FILE = 102;

    private WebView web;
    private File tilesDir;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingSave;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tilesDir = new File(getFilesDir(), "tiles");
        //noinspection ResultOfMethodCallIgnored
        tilesDir.mkdirs();
        prefs = getSharedPreferences("cdmap", MODE_PRIVATE);
        if (prefs.getString("id", null) == null) prefs.edit().putString("id", "a" + randDigits(12)).apply();
        if (prefs.getString("code", null) == null) prefs.edit().putString("code", randDigits(6)).apply();

        getWindow().setStatusBarColor(Color.parseColor("#16181c"));
        getWindow().setNavigationBarColor(Color.parseColor("#16181c"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#7f8083"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);

        // /assets/tiles/... comes from the tiles copied from the PC; everything else from the APK
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/tiles/", new WebViewAssetLoader.InternalStoragePathHandler(this, tilesDir))
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // stay on the map; external links (e.g. pin screenshots) are not opened inside the app
                return !request.getUrl().toString().startsWith("https://appassets.androidplatform.net/");
            }
        });
        // enables alert/confirm/prompt dialogs and the file picker used by "Restore"
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "Choose a progress backup (.json)"), REQ_OPEN_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "CDApp");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_FILE) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
        } else if (requestCode == REQ_SAVE_FILE) {
            String json = pendingSave;
            pendingSave = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || json == null) return;
            try (OutputStream os = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (os == null) throw new Exception("no stream");
                os.write(json.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Progress backup saved", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Could not save backup: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        startHost();
    }

    @Override
    protected void onPause() {
        stopHost();
        web.onPause();
        super.onPause();
    }

    // ---------------------------------------------------------------------------------------
    // Sync host: while the app is open, other devices on the Wi-Fi can find this one and sync with it.

    private static final SecureRandom RNG = new SecureRandom();
    private volatile ServerSocket host;
    private volatile DatagramSocket hostUdp;
    private WifiManager.MulticastLock hostLock;
    private final ExecutorService hostPool = Executors.newFixedThreadPool(4);
    private final Map<String, List<Long>> failures = new HashMap<>();

    private static String randDigits(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(RNG.nextInt(10));
        return sb.toString();
    }

    private String deviceName() {
        String n = null;
        try {
            n = Settings.Global.getString(getContentResolver(), "device_name");
        } catch (Exception ignored) {
        }
        if (n == null || n.isEmpty()) n = Build.MANUFACTURER + " " + Build.MODEL;
        return n;
    }

    private void startHost() {
        if (host != null) return;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                hostLock = wm.createMulticastLock("cdmap-host");
                hostLock.acquire();
            }
        } catch (Exception ignored) {
        }
        new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(HTTP_PORT));
                host = ss;
                while (!ss.isClosed()) {
                    final Socket s = ss.accept();
                    hostPool.execute(() -> handle(s));
                }
            } catch (Exception ignored) {
            }
            host = null;
        }).start();
        new Thread(() -> {
            try {
                DatagramSocket u = new DatagramSocket(null);
                u.setReuseAddress(true);
                u.bind(new InetSocketAddress(UDP_PORT));
                hostUdp = u;
                byte[] buf = new byte[512];
                while (!u.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    u.receive(p);
                    if (!"CDMAP_DISCOVER".equals(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim()))
                        continue;
                    JSONObject o = new JSONObject();
                    o.put("app", "cdmap");
                    o.put("id", prefs.getString("id", ""));
                    o.put("name", deviceName());
                    o.put("port", HTTP_PORT);
                    o.put("kind", "android");
                    byte[] r = o.toString().getBytes(StandardCharsets.UTF_8);
                    u.send(new DatagramPacket(r, r.length, p.getAddress(), p.getPort()));
                }
            } catch (Exception ignored) {
            }
            hostUdp = null;
        }).start();
    }

    private void stopHost() {
        try {
            if (host != null) host.close();
        } catch (Exception ignored) {
        }
        try {
            if (hostUdp != null) hostUdp.close();
        } catch (Exception ignored) {
        }
        host = null;
        hostUdp = null;
        if (hostLock != null && hostLock.isHeld()) hostLock.release();
    }

    private boolean authorized(String ip, String code) {
        synchronized (failures) {
            List<Long> recent = new ArrayList<>();
            List<Long> old = failures.get(ip);
            if (old != null) for (Long t : old) if (System.currentTimeMillis() - t < 600000) recent.add(t);
            if (recent.size() >= 10) {
                failures.put(ip, recent);
                return false;
            }
            if (code != null && code.equals(prefs.getString("code", ""))) return true;
            recent.add(System.currentTimeMillis());
            failures.put(ip, recent);
            return false;
        }
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new Exception("line too long");
        }
        return sb.toString();
    }

    private static void respond(Socket s, int status, String type, byte[] body) throws Exception {
        String reason = status == 200 ? "OK" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : "Error";
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(20000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String[] req = readLine(in).split(" ");
            if (req.length < 2) return;
            String method = req[0], target = req[1];
            Map<String, String> h = new HashMap<>();
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) h.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            String code = h.get("x-pair-code");
            if (code == null) for (String kv : query.split("&")) if (kv.startsWith("k=")) code = kv.substring(2);
            String ip = s.getInetAddress().getHostAddress();
            if (!authorized(ip, code)) {
                respond(s, 403, "text/plain", "wrong pairing code".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("POST".equals(method) && "/api/sync".equals(path)) {
                int len = Integer.parseInt(h.containsKey("content-length") ? h.get("content-length") : "0");
                if (len < 0 || len > 64 * 1024 * 1024) throw new Exception("bad length");
                byte[] body = new byte[len];
                int off = 0;
                while (off < len) {
                    int r = in.read(body, off, len - off);
                    if (r < 0) break;
                    off += r;
                }
                JSONObject b = new JSONObject(new String(body, 0, off, StandardCharsets.UTF_8));
                b.put("ip", ip);
                String merged = callPage(b.toString());
                if (merged == null) respond(s, 503, "text/plain", "app busy".getBytes(StandardCharsets.UTF_8));
                else respond(s, 200, "application/json", merged.getBytes(StandardCharsets.UTF_8));
            } else if ("GET".equals(method) && "/api/tiles-manifest".equals(path)) {
                JSONArray files = new JSONArray();
                int max = 0;
                File[] zs = tilesDir.listFiles();
                if (zs != null) for (File z : zs) {
                    if (!z.isDirectory()) continue;
                    File[] fs = z.listFiles();
                    if (fs == null) continue;
                    for (File f : fs) {
                        if (!f.getName().endsWith(".webp")) continue;
                        files.put(z.getName() + "/" + f.getName());
                        try {
                            max = Math.max(max, Integer.parseInt(z.getName()));
                        } catch (Exception ignored) {
                        }
                    }
                }
                JSONObject o = new JSONObject();
                o.put("max", max);
                o.put("files", files);
                respond(s, 200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
            } else if ("GET".equals(method) && path.startsWith("/tiles/") && !path.contains("..")) {
                File f = new File(tilesDir, path.substring(7));
                if (!f.isFile()) {
                    respond(s, 404, "text/plain", new byte[0]);
                    return;
                }
                byte[] data = new byte[(int) f.length()];
                try (FileInputStream fi = new FileInputStream(f)) {
                    int off = 0;
                    while (off < data.length) {
                        int r = fi.read(data, off, data.length - off);
                        if (r < 0) break;
                        off += r;
                    }
                }
                respond(s, 200, "image/webp", data);
            } else {
                respond(s, 404, "text/plain", new byte[0]);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Hand an incoming sync to the map page; returns the merged state JSON (or null). */
    private String callPage(String json) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        runOnUiThread(() -> web.evaluateJavascript(
                "window.__cdIncoming ? window.__cdIncoming(" + JSONObject.quote(json) + ") : null",
                v -> {
                    try {
                        Object o = new JSONTokener(v).nextValue();
                        out[0] = (o instanceof String) ? (String) o : null;
                    } catch (Exception e) {
                        out[0] = null;
                    }
                    latch.countDown();
                }));
        latch.await(15, TimeUnit.SECONDS);
        return out[0];
    }

    // ---------------------------------------------------------------------------------------

    private void reply(final String cb, final String json) {
        if (cb == null || !cb.matches("[A-Za-z0-9_]+")) return;
        runOnUiThread(() -> web.evaluateJavascript(
                "window['" + cb + "'] && window['" + cb + "'](" + JSONObject.quote(json) + ")", null));
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private static List<InetAddress> broadcastTargets() {
        List<InetAddress> list = new ArrayList<>();
        try {
            list.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !list.contains(b)) list.add(b);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private class Bridge {

        /** Look for the Windows app on the current Wi-Fi. Replies with a JSON array of {ip, port, name, id}. */
        @JavascriptInterface
        public void discover(final String cb) {
            new Thread(() -> {
                JSONArray found = new JSONArray();
                Set<String> seen = new HashSet<>();
                WifiManager.MulticastLock lock = null;
                DatagramSocket sock = null;
                try {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        lock = wm.createMulticastLock("cdmap");
                        lock.acquire();
                    }
                    sock = new DatagramSocket();
                    sock.setBroadcast(true);
                    sock.setSoTimeout(300);
                    byte[] q = "CDMAP_DISCOVER".getBytes(StandardCharsets.UTF_8);
                    List<InetAddress> targets = broadcastTargets();
                    long end = System.currentTimeMillis() + 2000;
                    long nextSend = 0;
                    while (System.currentTimeMillis() < end) {
                        if (System.currentTimeMillis() >= nextSend) {
                            for (InetAddress t : targets) {
                                try {
                                    sock.send(new DatagramPacket(q, q.length, t, UDP_PORT));
                                } catch (Exception ignored) {
                                }
                            }
                            nextSend = System.currentTimeMillis() + 700;
                        }
                        try {
                            DatagramPacket p = new DatagramPacket(new byte[1024], 1024);
                            sock.receive(p);
                            JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                            if (!"cdmap".equals(o.optString("app"))) continue;
                            String ip = p.getAddress().getHostAddress();
                            if (seen.add(o.optString("id") + "@" + ip)) {
                                o.put("ip", ip);
                                found.put(o);
                            }
                        } catch (SocketTimeoutException ignored) {
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (sock != null) sock.close();
                    if (lock != null && lock.isHeld()) lock.release();
                }
                reply(cb, found.toString());
            }).start();
        }

        /** Simple HTTP request to the PC app. Replies with {status, body} (status 0 = could not connect). */
        @JavascriptInterface
        public void request(final String method, final String url, final String headersJson, final String body, final String cb) {
            new Thread(() -> {
                JSONObject res = new JSONObject();
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(30000);
                    if (headersJson != null && !headersJson.isEmpty()) {
                        JSONObject h = new JSONObject(headersJson);
                        Iterator<String> keys = h.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            c.setRequestProperty(k, h.getString(k));
                        }
                    }
                    if (body != null && !body.isEmpty()) {
                        c.setDoOutput(true);
                        byte[] b = body.getBytes(StandardCharsets.UTF_8);
                        c.setFixedLengthStreamingMode(b.length);
                        try (OutputStream os = c.getOutputStream()) {
                            os.write(b);
                        }
                    }
                    int code = c.getResponseCode();
                    String text = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
                    res.put("status", code);
                    res.put("body", text);
                } catch (Exception e) {
                    try {
                        res.put("status", 0);
                        res.put("error", String.valueOf(e.getMessage()));
                    } catch (Exception ignored) {
                    }
                } finally {
                    if (c != null) c.disconnect();
                }
                reply(cb, res.toString());
            }).start();
        }

        /** "Back up progress": let the user pick where to save the backup file. */
        @JavascriptInterface
        public void saveBackup(final String json, final String fileName) {
            runOnUiThread(() -> {
                pendingSave = json;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, fileName);
                try {
                    startActivityForResult(i, REQ_SAVE_FILE);
                } catch (Exception e) {
                    pendingSave = null;
                    Toast.makeText(MainActivity.this, "No file app available to save the backup", Toast.LENGTH_LONG).show();
                }
            });
        }

        /** This device's id, name and sync code. */
        @JavascriptInterface
        public String selfInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", prefs.getString("id", ""));
                o.put("name", deviceName());
                o.put("code", prefs.getString("code", ""));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** Join a sync group by adopting another device's code. */
        @JavascriptInterface
        public void setCode(String code) {
            if (code != null && code.matches("[0-9]{6}")) prefs.edit().putString("code", code).apply();
        }

        /** Leave all groups: pick a fresh code. */
        @JavascriptInterface
        public void newCode() {
            prefs.edit().putString("code", randDigits(6)).apply();
        }

        /** How many HD tiles are stored on this device. */
        @JavascriptInterface
        public String tileInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("count", prefs.getInt("tileCount", 0));
                o.put("max", prefs.getInt("tileMax", 0));
                return o.toString();
            } catch (Exception e) {
                return "{\"count\":0}";
            }
        }

        /** Copy the HD tiles from the PC app (only files this device doesn't have yet). */
        @JavascriptInterface
        public void downloadTiles(final String base, final String code, final String cb) {
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/tiles-manifest").openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(60000);
                    c.setRequestProperty("X-Pair-Code", code);
                    int st = c.getResponseCode();
                    if (st != 200) {
                        reply(cb, "{\"error\":\"PC answered " + st + "\"}");
                        return;
                    }
                    JSONObject man = new JSONObject(readAll(c.getInputStream()));
                    c.disconnect();
                    final JSONArray files = man.getJSONArray("files");
                    final int max = man.optInt("max", 0);
                    final int total = files.length();
                    if (total == 0) {
                        reply(cb, "{\"error\":\"the PC app has no tiles folder next to it\"}");
                        return;
                    }
                    final AtomicInteger done = new AtomicInteger(), saved = new AtomicInteger(), failed = new AtomicInteger();
                    ExecutorService pool = Executors.newFixedThreadPool(6);
                    for (int i = 0; i < total; i++) {
                        final String rel = files.getString(i);
                        pool.execute(() -> {
                            try {
                                if (rel.contains("..")) return;
                                File f = new File(tilesDir, rel);
                                if (!(f.exists() && f.length() > 0)) {
                                    //noinspection ResultOfMethodCallIgnored
                                    f.getParentFile().mkdirs();
                                    HttpURLConnection t = (HttpURLConnection) new URL(base + "/tiles/" + rel).openConnection();
                                    t.setConnectTimeout(4000);
                                    t.setReadTimeout(20000);
                                    t.setRequestProperty("X-Pair-Code", code);
                                    if (t.getResponseCode() == 200) {
                                        File tmp = new File(f.getPath() + ".part");
                                        try (InputStream in = t.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                                            byte[] buf = new byte[16384];
                                            int n;
                                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                                        }
                                        //noinspection ResultOfMethodCallIgnored
                                        tmp.renameTo(f);
                                        saved.incrementAndGet();
                                    } else failed.incrementAndGet();
                                    t.disconnect();
                                }
                            } catch (Exception e) {
                                failed.incrementAndGet();
                            }
                            int d = done.incrementAndGet();
                            if (d % 40 == 0) reply(cb, "{\"done\":" + d + ",\"total\":" + total + "}");
                        });
                    }
                    pool.shutdown();
                    //noinspection ResultOfMethodCallIgnored
                    pool.awaitTermination(2, TimeUnit.HOURS);

                    try (FileOutputStream info = new FileOutputStream(new File(tilesDir, "info.js"))) {
                        info.write(("window.CD_DETAIL_MAX=" + max + ";").getBytes(StandardCharsets.UTF_8));
                    }
                    prefs.edit().putInt("tileCount", total - failed.get()).putInt("tileMax", max).apply();
                    if (failed.get() > 0)
                        reply(cb, "{\"error\":\"" + failed.get() + " tiles failed - tap Get HD tiles again to retry\"}");
                    else
                        reply(cb, "{\"finished\":true,\"saved\":" + saved.get() + ",\"total\":" + total + "}");
                } catch (Exception e) {
                    reply(cb, "{\"error\":" + JSONObject.quote("could not reach PC (" + e.getMessage() + ")") + "}");
                }
            }).start();
        }
    }
}
