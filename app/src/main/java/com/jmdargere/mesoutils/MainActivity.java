package com.jmdargere.mesoutils;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedDispatcher;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * MES OUTILS : affiche la page d'accueil (http://127.0.0.1:8800) en plein ecran
 * et, si elle ne tourne pas, la demarre dans Termux (RUN_COMMAND).
 */
public class MainActivity extends Activity {

    static final String HUB = "http://127.0.0.1:8800/";
    static final String PERM = "com.termux.permission.RUN_COMMAND";
    static final String TERMUX_BIN = "/data/data/com.termux/files/usr/bin";
    static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    static final String CMD = "cd /storage/emulated/0/Download/MES_OUTILS_ANDROID && nohup "
            + TERMUX_BIN + "/python hub.py >/dev/null 2>&1 &";

    private WebView web;
    private ValueCallback<Uri[]> filePicker;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web.setBackgroundColor(Color.parseColor("#232a31"));

        // Android 15+ : l'appli est dessinee sous les barres systeme -> on laisse la place
        if (Build.VERSION.SDK_INT >= 30) {
            web.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        // Android 16 : le bouton retour passe par ce rappel (onBackPressed n'est plus appele)
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                        if (web.canGoBack()) {
                            web.goBack();
                        } else {
                            finish();
                        }
                    });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("retry://")) {
                    show(splash());
                    go();
                    return true;
                }
                return false;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && r.getUrl().toString().startsWith("http")) {
                    show(page("Cette page ne répond pas",
                            "<p>L'outil est peut-être arrêté.</p>"
                                    + "<p><a class='b' href='retry://go'>RETOUR À MES OUTILS</a></p>"));
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb,
                                             FileChooserParams p) {
                if (filePicker != null) {
                    filePicker.onReceiveValue(null);
                }
                filePicker = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(i, "Fichier"), 7);
                } catch (Exception e) {
                    filePicker = null;
                    return false;
                }
                return true;
            }
        });

        show(splash());
        go();
    }

    // ------------------------------------------------------------ demarrage
    private boolean hubUp() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", 8800), 300);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean hasPerm() {
        return checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
    }

    private void go() {
        if (busy) {
            return;
        }
        busy = true;
        new Thread(() -> {
            try {
                if (hubUp()) {
                    load();
                    return;
                }
                if (!hasPerm()) {
                    ui.post(() -> requestPermissions(new String[]{PERM}, 1));
                    return;
                }
                if (!startHub()) {
                    ui.post(() -> show(help("Termux n'a pas pu être lancé.")));
                    return;
                }
                for (int i = 0; i < 50; i++) {
                    Thread.sleep(500);
                    if (hubUp()) {
                        load();
                        return;
                    }
                }
                ui.post(() -> show(help("L'accueil ne démarre pas.")));
            } catch (Exception e) {
                ui.post(() -> show(help("Erreur : " + e.getMessage())));
            } finally {
                busy = false;
            }
        }).start();
    }

    private boolean startHub() {
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BIN + "/bash");
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", CMD});
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void load() {
        ui.post(() -> web.loadUrl(HUB));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        busy = false;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            go();
        } else {
            show(help("L'autorisation « Exécuter des commandes Termux » n'est pas donnée."));
        }
    }

    // --------------------------------------------------------------- pages
    private void show(String html) {
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private String page(String title, String body) {
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;padding:24px;background:#232a31;color:#fff;"
                + "font:18px system-ui,sans-serif}h1{font-size:26px}"
                + "code{background:#14181c;color:#cfe3d4;padding:2px 6px;border-radius:6px;font-size:15px;"
                + "word-break:break-all}.b{display:block;text-align:center;background:#2e9e44;color:#fff;"
                + "text-decoration:none;font-weight:800;border-radius:16px;padding:20px;margin-top:20px}"
                + "</style></head><body><h1>" + title + "</h1>" + body + "</body></html>";
    }

    private String splash() {
        return page("🏠 MES OUTILS", "<p>Démarrage…</p>");
    }

    private String help(String problem) {
        return page("🏠 MES OUTILS",
                "<p><b>" + problem + "</b></p>"
                        + "<p>1. Dans Termux, tape :<br>"
                        + "<code>echo \"allow-external-apps = true\" &gt;&gt; ~/.termux/termux.properties "
                        + "&amp;&amp; termux-reload-settings</code></p>"
                        + "<p>2. Paramètres, Applications, MES OUTILS, Autorisations : "
                        + "autorise « Exécuter des commandes dans l'environnement Termux ».</p>"
                        + "<p>3. Vérifie que le dossier <code>Download/MES_OUTILS_ANDROID</code> existe.</p>"
                        + "<p><a class='b' href='retry://go'>RÉESSAYER</a></p>");
    }

    // ------------------------------------------------------------- systeme
    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == 7 && filePicker != null) {
            Uri[] res = null;
            if (result == RESULT_OK && data != null) {
                ClipData cd = data.getClipData();
                if (cd != null) {
                    res = new Uri[cd.getItemCount()];
                    for (int k = 0; k < res.length; k++) {
                        res[k] = cd.getItemAt(k).getUri();
                    }
                } else if (data.getData() != null) {
                    res = new Uri[]{data.getData()};
                }
            }
            filePicker.onReceiveValue(res);
            filePicker = null;
            return;
        }
        super.onActivityResult(code, result, data);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
