package org.nmeabridge.mdns;

import android.app.Activity;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
  private static final String TYPE = "_http._tcp.";
  private NsdManager nsd;
  private NsdManager.DiscoveryListener discovery;
  private WifiManager.MulticastLock lock;
  private boolean searching = false, resolving = false, opened = false;
  private int generation = 0;
  private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
  private final Set<String> seen = new HashSet<>();
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private TextView status, logs;
  private LinearLayout list, panel;
  private WebView browser;

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    nsd = (NsdManager)getSystemService(NSD_SERVICE);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(16, 16, 16, 16);
    status = new TextView(this);
    status.setTextSize(17);
    Button scan = new Button(this); scan.setText("Найти снова");
    scan.setOnClickListener(v -> start());
    panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
    list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
    logs = new TextView(this); logs.setTextSize(12); logs.setTextIsSelectable(true);
    ScrollView sc = new ScrollView(this); sc.addView(logs);
    panel.addView(list);
    panel.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
    browser = new WebView(this);
    browser.getSettings().setJavaScriptEnabled(true);
    browser.getSettings().setDomStorageEnabled(true);
    browser.getSettings().setAllowFileAccess(false);
    browser.setWebViewClient(new WebViewClient());
    root.addView(scan);
    root.addView(status);
    root.addView(panel, new LinearLayout.LayoutParams(-1, 0, 1));
    root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
    browser.setVisibility(View.GONE);
    setContentView(root);
    start();
  }

  private void log(String m) {
    logs.append(m + "\n");
    if (logs.length() > 12000) logs.setText(logs.getText().subSequence(logs.length()-9000,logs.length()));
  }

  private void start() {
    stop();
    int token = ++generation;
    queue.clear(); seen.clear(); resolving = false; opened = false;
    list.removeAllViews(); logs.setText(""); status.setText("Поиск mDNS " + TYPE);
    browser.stopLoading(); browser.setVisibility(View.GONE); panel.setVisibility(View.VISIBLE);
    try {
      WifiManager wifi = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
      if (wifi != null) {
        lock = wifi.createMulticastLock("NMEABridgeDiscovery");
        lock.setReferenceCounted(false); lock.acquire();
        log("MulticastLock: OK");
      }
    } catch(Exception e) { log("MulticastLock: " + e); }
    if (nsd == null) { status.setText("NsdManager недоступен"); return; }
    discovery = new NsdManager.DiscoveryListener() {
      public void onDiscoveryStarted(String t) { runOnUiThread(() -> log("Поиск запущен")); }
      public void onDiscoveryStopped(String t) { }
      public void onServiceFound(NsdServiceInfo s) {
        runOnUiThread(() -> {
          if (token != generation || opened) return;
          String key=s.getServiceName()+"|"+s.getServiceType();
          if (!seen.add(key)) return;
          log("Найден: " + s.getServiceName());
          Button button = new Button(MainActivity.this);
          button.setAllCaps(false);
          button.setText(s.getServiceName() + " — проверить HTTP");
          button.setOnClickListener(v -> { queue.addLast(s); next(token); });
          list.addView(button);
          String name=s.getServiceName().toLowerCase(java.util.Locale.ROOT);
          if (name.contains("nmea") || name.contains("bridge")) {
            queue.addLast(s); next(token);
          }
        });
      }
      public void onServiceLost(NsdServiceInfo s) { runOnUiThread(() -> log("Потерян: "+s.getServiceName())); }
      public void onStartDiscoveryFailed(String t, int code) {
        runOnUiThread(() -> { if (token==generation) { status.setText("Ошибка поиска "+code); stop(); } });
      }
      public void onStopDiscoveryFailed(String t, int code) { }
    };
    try {
      nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
      searching = true;
    } catch(Exception e) { log("Ошибка: "+e); stop(); }
  }

  private void next(int token) {
    if (resolving || opened || token != generation) return;
    NsdServiceInfo s = queue.pollFirst();
    if (s == null) return;
    resolving = true;
    status.setText("Определение IP: " + s.getServiceName());
    try {
      nsd.resolveService(s, new NsdManager.ResolveListener() {
        public void onResolveFailed(NsdServiceInfo i, int code) {
          runOnUiThread(() -> { if(token!=generation) return; log("Resolve error: "+code); resolving=false; next(token); });
        }
        public void onServiceResolved(NsdServiceInfo i) {
          runOnUiThread(() -> probe(i,token));
        }
      });
    } catch(Exception e) { log("Resolve error: "+e); resolving=false; next(token); }
  }

  private void probe(NsdServiceInfo s, int token) {
    InetAddress host = s.getHost();
    int port = s.getPort();
    if (!(host instanceof Inet4Address) || port < 1) {
      log("Нет IPv4 или корректного порта");
      resolving=false; next(token); return;
    }
    String url = "http://" + host.getHostAddress() + ":" + port + "/";
    status.setText("Проверяю " + url);
    executor.execute(() -> {
      int code=-1; String error="";
      HttpURLConnection connection=null;
      try {
        connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(2200);
        connection.setReadTimeout(2200);
        connection.setInstanceFollowRedirects(false);
        code=connection.getResponseCode();
      } catch(Exception e) { error=e.toString(); }
      finally { if(connection!=null) connection.disconnect(); }
      final int result=code; final String reason=error;
      runOnUiThread(() -> {
        if(token!=generation || opened) return;
        if(result>=200 && result<400) {
          opened=true; status.setText("HTTP "+result+" · "+url);
          log("HTTP доступен: "+url);
          stop();
          panel.setVisibility(View.GONE);
          browser.setVisibility(View.VISIBLE);
          browser.loadUrl(url);
        } else {
          log("HTTP недоступен: "+url+" "+result+" "+reason);
          status.setText("HTTP не ответил. Поиск продолжается.");
          resolving=false; next(token);
        }
      });
    });
  }

  private void stop() {
    if(nsd!=null && searching && discovery!=null) {
      try { nsd.stopServiceDiscovery(discovery); } catch(Exception ignored) {}
    }
    discovery=null; searching=false;
    if(lock!=null) {
      try { if(lock.isHeld()) lock.release(); } catch(Exception ignored) {}
      lock=null;
    }
  }
  @Override public void onBackPressed() {
    if(opened && browser.canGoBack()) browser.goBack();
    else if(opened) start();
    else super.onBackPressed();
  }
  @Override protected void onDestroy() {
    generation++; stop(); executor.shutdownNow(); browser.destroy(); super.onDestroy();
  }
}
