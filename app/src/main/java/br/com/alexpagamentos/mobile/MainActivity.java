package br.com.alexpagamentos.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebChromeClient;
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
        NotificationReceiver.createChannels(this);
        NotificationReceiver.schedule(this);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFY_PERMISSION);
        } else NotificationReceiver.checkAndNotify(this,false);

        w=new WebView(this); w.setBackgroundColor(Color.rgb(11,13,16)); setContentView(w);
        WebSettings s=w.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true); s.setAllowFileAccess(true);
        w.setWebViewClient(new WebViewClient()); w.setWebChromeClient(new WebChromeClient());
        w.addJavascriptInterface(new NativeBridge(this),"AlexNative");
        w.loadUrl("file:///android_asset/index.html");
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==NOTIFY_PERMISSION && grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED){
            NotificationReceiver.checkAndNotify(this,false);
        }
    }
}
