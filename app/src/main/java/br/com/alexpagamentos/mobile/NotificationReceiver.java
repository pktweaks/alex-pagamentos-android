package br.com.alexpagamentos.mobile;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class NotificationReceiver extends BroadcastReceiver {
    public static final String CHANNEL_OVERDUE = "alex_atrasados_v2";
    public static final String CHANNEL_TODAY = "alex_vencimentos_v1";
    private static final int REQ_ALARM = 3301;

    @Override public void onReceive(Context context, Intent intent) {
        createChannels(context);
        checkAndNotify(context, false);
        schedule(context);
    }

    public static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Uri overdueSound = Uri.parse("android.resource://" + c.getPackageName() + "/" + R.raw.cliente_atrasado);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel overdue = new NotificationChannel(CHANNEL_OVERDUE, "Clientes atrasados", NotificationManager.IMPORTANCE_HIGH);
        overdue.setDescription("Alertas de clientes com cobrança atrasada");
        overdue.enableVibration(true);
        overdue.setVibrationPattern(new long[]{0, 220, 100, 220});
        overdue.setSound(overdueSound, attrs);

        NotificationChannel today = new NotificationChannel(CHANNEL_TODAY, "Vencimentos de hoje", NotificationManager.IMPORTANCE_DEFAULT);
        today.setDescription("Lembretes de cobranças que vencem hoje");
        today.enableVibration(true);
        today.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs);

        nm.createNotificationChannel(overdue);
        nm.createNotificationChannel(today);
    }

    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(c, NotificationReceiver.class).setAction("br.com.alexpagamentos.CHECK_ALERTS");
        PendingIntent pi = PendingIntent.getBroadcast(c, REQ_ALARM, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, 9);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.DAY_OF_YEAR, 1);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
    }

    public static void checkAndNotify(Context c, boolean forceTest) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        try {
            int overdueCount = 0, todayCount = 0;
            String firstOverdue = "", firstToday = "";
            if (!forceTest) {
                File f = new File(new File(c.getFilesDir(), "alex_pagamentos"), "alex-pagamentos.json");
                if (!f.exists()) return;
                String json = read(f);
                JSONObject root = new JSONObject(json);
                JSONArray clients = root.optJSONArray("clients");
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                if (clients != null) {
                    for (int i = 0; i < clients.length(); i++) {
                        JSONObject cl = clients.optJSONObject(i); if (cl == null) continue;
                        if (cl.optBoolean("closedPaid", false)) continue;
                        String due = cl.optString("dueDate", "");
                        String name = cl.optString("name", "Cliente");
                        if (due.compareTo(today) < 0) { overdueCount++; if (firstOverdue.isEmpty()) firstOverdue = name; }
                        else if (due.equals(today)) { todayCount++; if (firstToday.isEmpty()) firstToday = name; }
                    }
                }
            } else { overdueCount = 1; firstOverdue = "Cliente de teste"; }

            SharedPreferences sp = c.getSharedPreferences("alex_notifications", Context.MODE_PRIVATE);
            String d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (overdueCount > 0 && (forceTest || !d.equals(sp.getString("last_overdue", "")))) {
                String body = overdueCount == 1
                        ? firstOverdue + " está com pagamento atrasado."
                        : overdueCount + " clientes estão com pagamentos atrasados.";
                notify(c, 901, CHANNEL_OVERDUE, "CLIENTE ATRASADO", body, true);
                if (!forceTest) sp.edit().putString("last_overdue", d).apply();
            }
            if (!forceTest && todayCount > 0 && !d.equals(sp.getString("last_today", ""))) {
                String body = todayCount == 1
                        ? firstToday + " tem cobrança vencendo hoje."
                        : todayCount + " clientes têm cobrança vencendo hoje.";
                notify(c, 902, CHANNEL_TODAY, "Cobrança para hoje", body, false);
                sp.edit().putString("last_today", d).apply();
            }
        } catch (Exception ignored) {}
    }

    private static void notify(Context c, int id, String channel, String title, String body, boolean high) {
        NotificationManager nm = (NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(high ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
            if (high) b.setSound(Uri.parse("android.resource://" + c.getPackageName() + "/" + R.raw.cliente_atrasado));
            else b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
        }
        nm.notify(id, b.build());
    }

    private static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int)f.length()]; int o=0,r;
            while(o<b.length && (r=in.read(b,o,b.length-o))>0) o+=r;
            return new String(b,0,o, StandardCharsets.UTF_8);
        }
    }
}
