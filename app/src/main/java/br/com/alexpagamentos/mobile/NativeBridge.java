package br.com.alexpagamentos.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NativeBridge {
    private final Activity c;
    private final File dir, data, backs;

    public NativeBridge(Activity c) {
        this.c = c;
        dir = new File(c.getFilesDir(), "alex_pagamentos");
        data = new File(dir, "alex-pagamentos.json");
        backs = new File(dir, "backups");
        dir.mkdirs(); backs.mkdirs();
    }

    @JavascriptInterface public String loadData() {
        try { return data.exists() ? read(data) : ""; } catch (Exception e) { return ""; }
    }

    @JavascriptInterface public String saveData(String json) {
        try {
            new JSONObject(json);
            File tmp = new File(dir, "data.tmp");
            write(tmp, json);
            if (data.exists()) copy(data, new File(backs, "ultimo-estado-valido.json"));
            if (data.exists()) data.delete();
            if (!tmp.renameTo(data)) write(data, json);
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            write(new File(backs, "backup-" + day + ".json"), json);
            cleanup();
            return "{\"ok\":true}";
        } catch (Exception e) { return "{\"ok\":false}"; }
    }

    @JavascriptInterface public String backupInfo() {
        File[] f = backs.listFiles((d,n)->n.endsWith(".json")); int n=f==null?0:f.length; String last="";
        if(f!=null&&f.length>0){Arrays.sort(f, Comparator.comparingLong(File::lastModified).reversed());last=new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(new Date(f[0].lastModified()));}
        return "{\"count\":"+n+",\"newest\":\""+last+"\"}";
    }

    @JavascriptInterface public void callPhone(String p) {
        String d=p==null?"":p.replaceAll("[^0-9+]",""); if(d.isEmpty())return;
        Intent i=new Intent(Intent.ACTION_DIAL, Uri.parse("tel:"+d)); c.startActivity(i);
    }

    @JavascriptInterface public String shareBackupWhatsApp() {
        try {
            if (!data.exists()) return "{\"ok\":false,\"message\":\"Nenhum dado salvo ainda.\"}";
            File out = prepareShareFile("ALEX-PAGAMENTOS-BACKUP-" + stamp() + ".json");
            copy(data, out);
            shareFile(out, "application/json", "Backup do ALEX PAGAMENTOS");
            return "{\"ok\":true}";
        } catch (Exception e) { return "{\"ok\":false,\"message\":\"Não foi possível criar o backup.\"}"; }
    }

    @JavascriptInterface public String shareClientsPdfWhatsApp() {
        try {
            if (!data.exists()) return "{\"ok\":false,\"message\":\"Cadastre um cliente primeiro.\"}";
            JSONObject root = new JSONObject(read(data));
            JSONArray clients = root.optJSONArray("clients");
            File out = prepareShareFile("ALEX-PAGAMENTOS-CLIENTES-" + stamp() + ".pdf");
            createClientsPdf(clients == null ? new JSONArray() : clients, out);
            shareFile(out, "application/pdf", "Planilha de clientes - ALEX PAGAMENTOS");
            return "{\"ok\":true}";
        } catch (Exception e) { return "{\"ok\":false,\"message\":\"Não foi possível gerar o PDF.\"}"; }
    }

    @JavascriptInterface public void testOverdueNotification() {
        c.runOnUiThread(() -> NotificationReceiver.checkAndNotify(c, true));
    }

    @JavascriptInterface public void refreshNotifications() {
        c.runOnUiThread(() -> { NotificationReceiver.createChannels(c); NotificationReceiver.schedule(c); NotificationReceiver.checkAndNotify(c, false); });
    }

    private File prepareShareFile(String name) throws Exception {
        File base = new File(c.getCacheDir(), "alex_share");
        if (!base.exists() && !base.mkdirs()) throw new Exception("Falha ao criar pasta");
        File[] old = base.listFiles();
        if (old != null) for (File f : old) if (System.currentTimeMillis() - f.lastModified() > 24L*60L*60L*1000L) f.delete();
        return new File(base, name);
    }

    private void shareFile(File file, String mime, String title) {
        Uri uri = Uri.parse("content://" + c.getPackageName() + ".files/" + Uri.encode(file.getName()));
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_TEXT, title);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent direct = new Intent(send);
        if (isInstalled("com.whatsapp")) direct.setPackage("com.whatsapp");
        else if (isInstalled("com.whatsapp.w4b")) direct.setPackage("com.whatsapp.w4b");
        else { c.startActivity(Intent.createChooser(send, "Compartilhar arquivo")); return; }
        try { c.startActivity(direct); }
        catch (Exception e) { c.startActivity(Intent.createChooser(send, "Compartilhar arquivo")); }
    }

    private boolean isInstalled(String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    private void createClientsPdf(JSONArray arr, File out) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        for (int i=0;i<arr.length();i++){ JSONObject x=arr.optJSONObject(i); if(x!=null) list.add(x); }
        list.sort((a,b)->a.optString("dueDate","").compareTo(b.optString("dueDate","")));

        PdfDocument doc = new PdfDocument();
        final int W=842,H=595, margin=34, rowH=28;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        NumberFormat brl = NumberFormat.getCurrencyInstance(new Locale("pt","BR"));
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int active=0, overdue=0; double total=0;
        for(JSONObject x:list){ if(!x.optBoolean("closedPaid",false)){active++; total+=x.optDouble("value",0); if(x.optString("dueDate","").compareTo(today)<0)overdue++;} }

        int index=0,pageNo=1;
        while(index<list.size() || (list.isEmpty() && pageNo==1)){
            PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(W,H,pageNo).create());
            Canvas cv=page.getCanvas(); cv.drawColor(Color.WHITE);
            p.setColor(Color.rgb(22,27,35)); p.setStyle(Paint.Style.FILL); cv.drawRoundRect(margin,24,W-margin,104,14,14,p);
            p.setColor(Color.WHITE); p.setTextSize(22); p.setFakeBoldText(true); cv.drawText("ALEX PAGAMENTOS",margin+18,57,p);
            p.setTextSize(11);p.setFakeBoldText(false);p.setColor(Color.rgb(205,213,224));cv.drawText("PLANILHA DE CLIENTES • "+new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(new Date()),margin+18,79,p);
            p.setColor(Color.rgb(36,43,53));p.setTextSize(11);cv.drawText("Clientes ativos: "+active+"   •   Atrasados: "+overdue+"   •   Total em aberto: "+brl.format(total),margin,128,p);

            float y=154;
            p.setColor(Color.rgb(235,239,244));cv.drawRoundRect(margin,y,W-margin,y+30,8,8,p);
            p.setColor(Color.rgb(55,64,76));p.setTextSize(10);p.setFakeBoldText(true);
            String[] heads={"CLIENTE","TELEFONE","VALOR","PRÓXIMA COBRANÇA","SITUAÇÃO"}; float[] xs={margin+10,250,390,500,655};
            for(int j=0;j<heads.length;j++)cv.drawText(heads[j],xs[j],y+19,p); p.setFakeBoldText(false); y+=36;

            int rows=0;
            while(index<list.size() && y+rowH<560 && rows<13){
                JSONObject x=list.get(index++); boolean closed=x.optBoolean("closedPaid",false); String due=x.optString("dueDate","");
                String status=closed?"PAGO / ENCERRADO":due.compareTo(today)<0?"ATRASADO":due.equals(today)?"VENCE HOJE":"AGENDADO";
                if(rows%2==0){p.setColor(Color.rgb(248,249,251));cv.drawRoundRect(margin,y-4,W-margin,y+rowH-4,6,6,p);}
                p.setTextSize(10);p.setColor(Color.rgb(30,35,43));
                cv.drawText(shorten(x.optString("name",""),31),xs[0],y+14,p);
                cv.drawText(shorten(formatPhone(x.optString("phone","")),18),xs[1],y+14,p);
                cv.drawText(brl.format(x.optDouble("value",0)),xs[2],y+14,p);
                cv.drawText(closed?"—":formatDate(due),xs[3],y+14,p);
                if(status.equals("ATRASADO"))p.setColor(Color.rgb(190,42,42)); else if(status.startsWith("PAGO"))p.setColor(Color.rgb(25,125,67)); else if(status.equals("VENCE HOJE"))p.setColor(Color.rgb(161,105,0)); else p.setColor(Color.rgb(78,88,102));
                p.setFakeBoldText(true);cv.drawText(status,xs[4],y+14,p);p.setFakeBoldText(false);
                y+=rowH;rows++;
            }
            p.setColor(Color.rgb(130,139,151));p.setTextSize(8);cv.drawText("Página "+pageNo, W-margin-45,H-17,p);
            if(list.isEmpty()){p.setColor(Color.rgb(100,108,120));p.setTextSize(13);cv.drawText("Nenhum cliente cadastrado.",margin,220,p);index=1;}
            doc.finishPage(page);pageNo++;
        }
        try(FileOutputStream fos=new FileOutputStream(out)){doc.writeTo(fos);} finally {doc.close();}
    }

    private static String shorten(String s,int max){ if(s==null)return""; return s.length()<=max?s:s.substring(0,max-1)+"…"; }
    private static String formatDate(String iso){ try{return new SimpleDateFormat("dd/MM/yyyy",Locale.US).format(new SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(iso));}catch(Exception e){return iso;} }
    private static String formatPhone(String v){ String d=v==null?"":v.replaceAll("\\D","");if(d.startsWith("55")&&d.length()>11)d=d.substring(2);if(d.length()==11)return"("+d.substring(0,2)+") "+d.substring(2,7)+"-"+d.substring(7);return v; }
    private static String stamp(){return new SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(new Date());}

    private void cleanup(){File[] f=backs.listFiles((d,n)->n.startsWith("backup-")&&n.endsWith(".json"));if(f==null||f.length<=30)return;Arrays.sort(f,Comparator.comparingLong(File::lastModified));for(int i=0;i<f.length-30;i++)f[i].delete();}
    private static String read(File f)throws Exception{try(FileInputStream in=new FileInputStream(f)){byte[] b=new byte[(int)f.length()];int o=0,r;while(o<b.length&&(r=in.read(b,o,b.length-o))>0)o+=r;return new String(b,0,o, StandardCharsets.UTF_8);}}
    private static void write(File f,String s)throws Exception{try(FileOutputStream o=new FileOutputStream(f,false)){o.write(s.getBytes(StandardCharsets.UTF_8));o.flush();o.getFD().sync();}}
    private static void copy(File a,File b)throws Exception{try(FileInputStream i=new FileInputStream(a);FileOutputStream o=new FileOutputStream(b)){byte[] x=new byte[8192];int n;while((n=i.read(x))>0)o.write(x,0,n);o.flush();}}
}
