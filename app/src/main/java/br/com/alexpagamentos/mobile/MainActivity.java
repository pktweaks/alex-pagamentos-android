package br.com.alexpagamentos.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView w;
    private static final int NOTIFY_PERMISSION = 44;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);

        getWindow().setStatusBarColor(Color.rgb(11,13,16));
        getWindow().setNavigationBarColor(Color.rgb(11,13,16));

        // Primeiro monta a interface. Assim notificações nunca bloqueiam a tela do app.
        w = new WebView(this);
        w.setBackgroundColor(Color.rgb(11,13,16));
        setContentView(w);

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        w.setWebChromeClient(new WebChromeClient());
        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.setVisibility(View.VISIBLE);
            }

            @Override public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    showLoadError();
                }
            }
        });

        w.addJavascriptInterface(new NativeBridge(this), "AlexNative");
        w.loadUrl("file:///android_asset/index.html");

        // Configura alertas depois que o WebView já começou a carregar.
        w.postDelayed(() -> {
            try {
                NotificationReceiver.createChannels(this);
                NotificationReceiver.schedule(this);

                if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            NOTIFY_PERMISSION
                    );
                } else {
                    NotificationReceiver.checkAndNotify(this, false);
                }
            } catch (Exception ignored) {
                // A interface continua funcionando mesmo se o Android falhar nos alertas.
            }
        }, 600);
    }

    private void showLoadError() {
        if (w == null) return;
        String html =
                "<html><body style='margin:0;background:#0b0d10;color:#fff;font-family:sans-serif'>" +
                "<div style='padding:28px'><h2>ALEX PAGAMENTOS</h2>" +
                "<p style='color:#aab3c0'>Não foi possível carregar a tela do app.</p>" +
                "<button onclick='location.href=\"file:///android_asset/index.html\"' " +
                "style='padding:14px;border:0;border-radius:12px;font-weight:bold'>Tentar novamente</button>" +
                "</div></body></html>";
        w.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }


    private void forceSaveBeforeLeaving() {
        try {
            if (w != null) {
                w.evaluateJavascript(
                        "try{window.AlexForceSave&&window.AlexForceSave()}catch(e){}",
                        null
                );
            }
        } catch (Exception ignored) {}
    }

    @Override protected void onPause() {
        forceSaveBeforeLeaving();
        super.onPause();
    }

    @Override protected void onStop() {
        forceSaveBeforeLeaving();
        super.onStop();
    }

    @Override public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFY_PERMISSION &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            try {
                NotificationReceiver.checkAndNotify(this, false);
            } catch (Exception ignored) {}
        }
    }
}
