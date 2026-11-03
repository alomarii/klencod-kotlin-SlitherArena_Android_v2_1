package com.slither.arena;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Slither Arena
 * تصميم وبرمجة محمد العمري 730215825
 *
 * اللاعب المضيف يفتح منفذ TCP ويعرض كود من 4 أرقام.
 * اللاعب المنضم يكتب الكود فقط: يتصل التطبيق تلقائياً ببوابة الهوت سبوت (أو يبحث عبر UDP على نفس الشبكة).
 * لا يوجد أي تبادل أكواد طويلة ولا تأكيد مزدوج.
 */
public class MainActivity extends Activity {
    private static final int PORT = 45454, DPORT = 45455;

    private WebView web;
    private boolean destroyed = false;

    private final Object lock = new Object();
    private int gen = 0;
    private ServerSocket server;
    private DatagramSocket disc;
    private Conn conn;
    private WifiManager.MulticastLock mlock;
    private boolean bound = false;

    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();

    /** اتصال واحد مع اللاعب الآخر */
    private static class Conn {
        final Socket s;
        final BufferedReader r;
        final OutputStream os;

        Conn(Socket s) throws java.io.IOException {
            this.s = s;
            this.r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            this.os = s.getOutputStream();
        }

        void close() {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** الجسر الذي تراه صفحة اللعبة باسم Android */
    public class Bridge {
        @JavascriptInterface
        public void host(String code) {
            if (code != null && code.matches("\\d{4}")) startHost(code);
        }

        @JavascriptInterface
        public void join(String code) {
            if (code != null && code.matches("\\d{4}")) startJoin(code);
        }

        @JavascriptInterface
        public void send(String msg) {
            sendMsg(msg);
        }

        @JavascriptInterface
        public void stop() {
            stopNet();
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#090b0e"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        immersive();
    }

    /* ---------------- أدوات ---------------- */

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!destroyed && web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    private void emit(String kind, String data) {
        js("window.onNet&&window.onNet(" + JSONObject.quote(kind) + "," + JSONObject.quote(data) + ")");
    }

    private boolean cur(int g) {
        synchronized (lock) { return g == gen; }
    }

    private static void closeQ(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Exception ignored) { }
    }

    private WifiManager wifi() {
        try {
            return (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    private static String ip(int v) {
        return (v & 255) + "." + ((v >> 8) & 255) + "." + ((v >> 16) & 255) + "." + ((v >> 24) & 255);
    }

    /** عنوان بوابة الشبكة = عنوان هاتف المضيف عندما يكون هاتفي متصلاً بالهوت سبوت الخاص به */
    @SuppressWarnings("deprecation")
    private String gatewayIp() {
        try {
            WifiManager wm = wifi();
            if (wm == null) return null;
            DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.gateway == 0) return null;
            return ip(d.gateway);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private String broadcastIp() {
        try {
            WifiManager wm = wifi();
            if (wm == null) return null;
            DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.ipAddress == 0 || d.netmask == 0) return null;
            return ip((d.ipAddress & d.netmask) | ~d.netmask);
        } catch (Exception e) {
            return null;
        }
    }

    /** يجعل الاتصال يمر عبر الواي فاي حتى لو كانت بيانات الجوال هي الشبكة الافتراضية */
    @SuppressWarnings("deprecation")
    private void bindWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    if (Build.VERSION.SDK_INT >= 23) cm.bindProcessToNetwork(n);
                    else ConnectivityManager.setProcessDefaultNetwork(n);
                    bound = true;
                    return;
                }
            }
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void unbindWifi() {
        if (!bound) return;
        bound = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            if (Build.VERSION.SDK_INT >= 23) cm.bindProcessToNetwork(null);
            else ConnectivityManager.setProcessDefaultNetwork(null);
        } catch (Exception ignored) { }
    }

    private void stopNet() {
        synchronized (lock) {
            gen++;
            closeQ(server);
            server = null;
            if (disc != null) { disc.close(); disc = null; }
            if (conn != null) { conn.close(); conn = null; }
            try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Exception ignored) { }
            mlock = null;
        }
        unbindWifi();
    }

    private int begin() {
        stopNet();
        synchronized (lock) { return gen; }
    }

    /* ---------------- إرسال ---------------- */

    private void sendMsg(String msg) {
        final Conn c;
        synchronized (lock) { c = conn; }
        if (c == null || msg == null) return;
        if (pending.get() > 10) return; // الشبكة بطيئة: نتخطى بعض الحزم بدل التراكم
        pending.incrementAndGet();
        final byte[] data = (msg + "\n").getBytes(StandardCharsets.UTF_8);
        writer.execute(new Runnable() {
            @Override public void run() {
                try {
                    c.os.write(data);
                    c.os.flush();
                } catch (Exception e) {
                    c.close();
                } finally {
                    pending.decrementAndGet();
                }
            }
        });
    }

    private void readLoop(Conn c, int g) {
        try {
            String line;
            while ((line = c.r.readLine()) != null) {
                if (!cur(g)) return;
                emit("m", line);
            }
        } catch (Exception ignored) { }
        if (cur(g)) emit("c", "");
    }

    /* ---------------- المضيف ---------------- */

    private void startHost(final String code) {
        final int g = begin();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    WifiManager wm = wifi();
                    if (wm != null) {
                        try {
                            WifiManager.MulticastLock ml = wm.createMulticastLock("slither");
                            ml.setReferenceCounted(false);
                            ml.acquire();
                            synchronized (lock) { mlock = ml; }
                        } catch (Exception ignored) { }
                    }
                    ServerSocket ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(PORT));

                    DatagramSocket ds = null;
                    try {
                        ds = new DatagramSocket(null);
                        ds.setReuseAddress(true);
                        ds.bind(new InetSocketAddress(DPORT));
                        ds.setBroadcast(true);
                    } catch (Exception e) {
                        closeQ(ds);
                        ds = null;
                    }

                    synchronized (lock) {
                        if (g != gen) {
                            closeQ(ss);
                            if (ds != null) ds.close();
                            return;
                        }
                        server = ss;
                        disc = ds;
                    }
                    if (ds != null) {
                        final DatagramSocket d2 = ds;
                        new Thread(new Runnable() {
                            @Override public void run() { discoveryLoop(d2, code, g); }
                        }).start();
                    }
                    emit("l", "");

                    while (cur(g)) {
                        Socket s = ss.accept();
                        Conn c = handshakeHost(s, code);
                        if (c == null) continue;
                        synchronized (lock) {
                            if (g != gen) { c.close(); return; }
                            conn = c;
                            closeQ(server);
                            server = null;
                            if (disc != null) { disc.close(); disc = null; }
                        }
                        emit("o", "");
                        readLoop(c, g);
                        return;
                    }
                } catch (Exception e) {
                    if (cur(g)) emit("e", "port");
                }
            }
        }).start();
    }

    private Conn handshakeHost(Socket s, String code) {
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(4000);
            Conn c = new Conn(s);
            String line = c.r.readLine();
            if (("HELLO|" + code).equals(line)) {
                c.os.write("OK\n".getBytes(StandardCharsets.UTF_8));
                c.os.flush();
                s.setSoTimeout(0);
                return c;
            }
            try {
                c.os.write("BAD\n".getBytes(StandardCharsets.UTF_8));
                c.os.flush();
            } catch (Exception ignored) { }
            c.close();
        } catch (Exception e) {
            closeQ(s);
        }
        return null;
    }

    private void discoveryLoop(DatagramSocket ds, String code, int g) {
        final String want = "SLITH|" + code;
        final byte[] reply = ("SLITH_OK|" + code).getBytes(StandardCharsets.UTF_8);
        byte[] buf = new byte[64];
        while (cur(g)) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ds.receive(p);
                String m = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (want.equals(m)) {
                    ds.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                }
            } catch (Exception e) {
                return; // أُغلق المقبس
            }
        }
    }

    /* ---------------- المنضم ---------------- */

    private void startJoin(final String code) {
        final int g = begin();
        new Thread(new Runnable() {
            @Override public void run() {
                int[] err = {0};
                Conn c = null;
                bindWifi();
                String gw = gatewayIp();
                if (gw != null) c = tryConnect(gw, code, err);
                if (c == null && err[0] == 0 && cur(g)) {
                    String ip = discover(code);
                    if (ip != null && !ip.equals(gw)) c = tryConnect(ip, code, err);
                }
                if (!cur(g)) {
                    if (c != null) c.close();
                    return;
                }
                if (c == null) {
                    emit("e", err[0] == 1 ? "bad" : "nf");
                    return;
                }
                synchronized (lock) {
                    if (g != gen) { c.close(); return; }
                    conn = c;
                }
                emit("o", "");
                readLoop(c, g);
            }
        }).start();
    }

    private Conn tryConnect(String ip, String code, int[] err) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, PORT), 2500);
            s.setTcpNoDelay(true);
            s.setSoTimeout(3500);
            Conn c = new Conn(s);
            c.os.write(("HELLO|" + code + "\n").getBytes(StandardCharsets.UTF_8));
            c.os.flush();
            String l = c.r.readLine();
            if ("OK".equals(l)) {
                s.setSoTimeout(0);
                return c;
            }
            err[0] = 1; // الكود خاطئ
            c.close();
        } catch (Exception e) {
            closeQ(s);
        }
        return null;
    }

    private String discover(String code) {
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            ds.setBroadcast(true);
            ds.setSoTimeout(900);
            byte[] msg = ("SLITH|" + code).getBytes(StandardCharsets.UTF_8);
            String bc = broadcastIp();
            for (int i = 0; i < 3; i++) {
                if (bc != null) {
                    try { ds.send(new DatagramPacket(msg, msg.length, InetAddress.getByName(bc), DPORT)); }
                    catch (Exception ignored) { }
                }
                try { ds.send(new DatagramPacket(msg, msg.length, InetAddress.getByName("255.255.255.255"), DPORT)); }
                catch (Exception ignored) { }
                try {
                    byte[] b = new byte[64];
                    DatagramPacket p = new DatagramPacket(b, b.length);
                    ds.receive(p);
                    String r = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (("SLITH_OK|" + code).equals(r)) return p.getAddress().getHostAddress();
                } catch (SocketTimeoutException ignored) { }
            }
        } catch (Exception ignored) {
        } finally {
            closeQ(ds);
        }
        return null;
    }

    /* ---------------- واجهة النظام ---------------- */

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("(window.onBack?window.onBack():false)", new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if (!"true".equals(v)) MainActivity.super.onBackPressed();
            }
        });
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }

    @Override protected void onDestroy() {
        destroyed = true;
        stopNet();
        writer.shutdownNow();
        web.destroy();
        super.onDestroy();
    }
}
