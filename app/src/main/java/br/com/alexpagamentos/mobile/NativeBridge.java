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
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        // 1) Tenta o arquivo principal.
        String main = readValidJson(data);
        if (main != null) return main;

        // 2) Se o principal estiver corrompido/incompleto, recupera o último estado válido.
        File valid = new File(backs, "ultimo-estado-valido.json");
        String recovered = readValidJson(valid);
        if (recovered != null) {
            try { write(data, recovered); } catch (Exception ignored) {}
            return recovered;
        }

        // 3) Última barreira: procura o backup JSON mais recente que ainda seja válido.
        File[] candidates = backs.listFiles((d,n) -> n.endsWith(".json"));
        if (candidates != null && candidates.length > 0) {
            Arrays.sort(candidates, Comparator.comparingLong(File::lastModified).reversed());
            for (File f : candidates) {
                String json = readValidJson(f);
                if (json != null) {
                    try { write(data, json); } catch (Exception ignored) {}
                    return json;
                }
            }
        }
        return "";
    }

    @JavascriptInterface public String saveData(String json) {
        try {
            // Só aceita um estado JSON íntegro. Nunca substitui dados válidos por conteúdo quebrado.
            new JSONObject(json);
            File tmp = new File(dir, "data.tmp");
            write(tmp, json);

            // Preserva o arquivo principal anterior apenas quando ele também é JSON válido.
            String oldValid = readValidJson(data);
            if (oldValid != null) write(new File(backs, "ultimo-estado-valido.json"), oldValid);

            if (data.exists() && !data.delete()) {
                // Se o Android não deixar substituir o arquivo, mantém o original e aborta sem perda.
                tmp.delete();
                return "{\"ok\":false}";
            }
            if (!tmp.renameTo(data)) {
                write(data, json);
                tmp.delete();
            }

            // Confirma que o arquivo final realmente ficou íntegro antes de declarar sucesso.
            if (readValidJson(data) == null) {
                if (oldValid != null) write(data, oldValid);
                return "{\"ok\":false}";
            }

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


    @JavascriptInterface public String exportExcel(String currentJson) {
        try {
            JSONObject root;
            if (currentJson != null && !currentJson.trim().isEmpty()) root = new JSONObject(currentJson);
            else if (data.exists()) root = new JSONObject(read(data));
            else return "{\"ok\":false,\"message\":\"Cadastre um cliente primeiro.\"}";

            // A planilha sempre usa o estado exato que está na tela. Antes de exportar,
            // salvamos e criamos um backup extra para reduzir o risco de perda de dados.
            String exactJson = root.toString();
            String saved = saveData(exactJson);
            if (!saved.contains("\"ok\":true")) {
                return "{\"ok\":false,\"message\":\"Não foi possível salvar os dados antes da exportação. Nada foi apagado.\"}";
            }
            write(new File(backs, "backup-export-" + stamp() + ".json"), exactJson);

            File out = prepareShareFile("ALEX-PAGAMENTOS-PLANILHA-" + stamp() + ".xlsx");
            createExcelWorkbook(root, out);
            if (!out.exists() || out.length() < 1500) throw new Exception("Arquivo XLSX inválido");
            shareFileChooser(out, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Exportar planilha do ALEX PAGAMENTOS");
            return "{\"ok\":true}";
        } catch (Exception e) {
            return "{\"ok\":false,\"message\":\"Não foi possível gerar a planilha. Seus dados continuam salvos no aplicativo.\"}";
        }
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


    private void shareFileChooser(File file, String mime, String title) {
        Uri uri = Uri.parse("content://" + c.getPackageName() + ".files/" + Uri.encode(file.getName()));
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_TEXT, title);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startActivity(Intent.createChooser(send, title));
    }

    private boolean isInstalled(String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }


    private void createExcelWorkbook(JSONObject root, File out) throws Exception {
        JSONArray ca = root.optJSONArray("clients");
        JSONArray pa = root.optJSONArray("payments");
        JSONArray na = root.optJSONArray("notes");
        if (ca == null) ca = new JSONArray();
        if (pa == null) pa = new JSONArray();
        if (na == null) na = new JSONArray();

        List<JSONObject> clients = jsonObjects(ca);
        List<JSONObject> payments = jsonObjects(pa);
        List<JSONObject> notes = jsonObjects(na);

        clients.sort((a,b) -> a.optString("name", "").compareToIgnoreCase(b.optString("name", "")));
        payments.sort((a,b) -> b.optString("paidAt", "").compareTo(a.optString("paidAt", "")));
        notes.sort((a,b) -> b.optString("updatedAt", "").compareTo(a.optString("updatedAt", "")));

        List<JSONObject> open = new ArrayList<>();
        for (JSONObject x : clients) if (!x.optBoolean("closedPaid", false)) open.add(x);
        open.sort((a,b) -> a.optString("dueDate", "9999-12-31").compareTo(b.optString("dueDate", "9999-12-31")));

        String[] sheetNames = {"Resumo", "Clientes", "Histórico", "Anotações", "Cobranças em aberto"};
        String[] sheets = {
                buildSummarySheet(clients, payments, notes, open),
                buildClientsSheet(clients),
                buildPaymentsSheet(payments),
                buildNotesSheet(notes),
                buildOpenSheet(open)
        };

        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            zipText(z, "[Content_Types].xml", contentTypesXml(sheetNames.length));
            zipText(z, "_rels/.rels", rootRelsXml());
            zipText(z, "docProps/app.xml", appXml(sheetNames));
            zipText(z, "docProps/core.xml", coreXml());
            zipText(z, "xl/workbook.xml", workbookXml(sheetNames));
            zipText(z, "xl/_rels/workbook.xml.rels", workbookRelsXml(sheetNames.length));
            zipText(z, "xl/styles.xml", stylesXml());
            for (int i = 0; i < sheets.length; i++) zipText(z, "xl/worksheets/sheet" + (i+1) + ".xml", sheets[i]);
        }
    }

    private static List<JSONObject> jsonObjects(JSONArray a) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null) out.add(x);
        }
        return out;
    }

    private static String buildSummarySheet(List<JSONObject> clients, List<JSONObject> payments, List<JSONObject> notes, List<JSONObject> open) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int closed = 0, overdue = 0, dueToday = 0;
        double totalOpen = 0, totalReceived = 0;
        for (JSONObject c : clients) {
            if (c.optBoolean("closedPaid", false)) closed++;
            else {
                totalOpen += safeNumber(c.optDouble("value", 0));
                String due = c.optString("dueDate", "");
                if (!due.isEmpty() && due.compareTo(today) < 0) overdue++;
                else if (due.equals(today)) dueToday++;
            }
        }
        for (JSONObject p : payments) totalReceived += safeNumber(p.optDouble("value", 0));

        StringBuilder b = sheetBegin("A1:B16", new double[]{34, 24}, 0);
        addText(b, "A1", "ALEX PAGAMENTOS", 1);
        addText(b, "A2", "Planilha completa • exportada em " + new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date()), 2);
        rowStart(b, 4, 25); addText(b, "A4", "INDICADOR", 3); addText(b, "B4", "VALOR", 3); rowEnd(b);
        summaryRow(b, 5, "Total de clientes", clients.size(), false);
        summaryRow(b, 6, "Clientes ativos", open.size(), false);
        summaryRow(b, 7, "Pagos / encerrados", closed, false);
        summaryRow(b, 8, "Vencem hoje", dueToday, false);
        summaryRow(b, 9, "Atrasados", overdue, false);
        summaryRow(b, 10, "Total em aberto", totalOpen, true);
        summaryRow(b, 11, "Total já recebido", totalReceived, true);
        summaryRow(b, 13, "Pagamentos no histórico", payments.size(), false);
        summaryRow(b, 14, "Anotações", notes.size(), false);
        summaryRow(b, 15, "Cobranças em aberto", open.size(), false);
        b.append("</sheetData><mergeCells count=\"2\"><mergeCell ref=\"A1:B1\"/><mergeCell ref=\"A2:B2\"/></mergeCells>");
        b.append(pageMargins()).append("</worksheet>");
        return b.toString();
    }

    private static void summaryRow(StringBuilder b, int r, String label, double value, boolean money) {
        rowStart(b, r, 22);
        addText(b, "A" + r, label, 11);
        addNumber(b, "B" + r, value, money ? 13 : 12);
        rowEnd(b);
    }

    private static String buildClientsSheet(List<JSONObject> clients) {
        final String[] h = {"CLIENTE", "TELEFONE", "VALOR", "PRÓXIMA COBRANÇA", "RECORRÊNCIA", "SITUAÇÃO", "ÚLTIMO PAGAMENTO", "OBSERVAÇÃO", "CADASTRADO EM", "ATUALIZADO EM", "CÓDIGO INTERNO"};
        int last = Math.max(4, 4 + clients.size());
        StringBuilder b = sheetBegin("A1:K" + last, new double[]{28,20,15,20,19,24,21,38,20,20,25}, 4);
        titleRows(b, "CLIENTES", "Todos os clientes cadastrados no aplicativo", 11);
        headerRow(b, h);
        int r = 5;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        for (JSONObject c : clients) {
            boolean closed = c.optBoolean("closedPaid", false);
            String due = c.optString("dueDate", "");
            String status = clientStatus(c, today);
            rowStart(b, r, 22);
            addText(b, "A"+r, c.optString("name", ""), 4);
            addText(b, "B"+r, formatPhone(c.optString("phone", "")), 4);
            addNumber(b, "C"+r, safeNumber(c.optDouble("value", 0)), 5);
            if (closed || due.isEmpty()) addText(b, "D"+r, "—", 4); else addDate(b, "D"+r, due, 6);
            addText(b, "E"+r, recurrenceLabel(c), 4);
            addText(b, "F"+r, status, statusStyle(status));
            addText(b, "G"+r, formatDateTime(c.optString("lastPaidAt", "")), 4);
            addText(b, "H"+r, c.optString("notes", ""), 14);
            addText(b, "I"+r, formatDateTime(c.optString("createdAt", "")), 4);
            addText(b, "J"+r, formatDateTime(c.optString("updatedAt", "")), 4);
            addText(b, "K"+r, c.optString("id", ""), 4);
            rowEnd(b); r++;
        }
        finishTableSheet(b, "A4:K" + last, "A1:K1", "A2:K2");
        return b.toString();
    }

    private static String buildPaymentsSheet(List<JSONObject> payments) {
        final String[] h = {"DATA DO PAGAMENTO", "CLIENTE", "VALOR RECEBIDO", "VENCIMENTO REFERENTE", "CÓDIGO DO CLIENTE", "CÓDIGO DO PAGAMENTO"};
        int last = Math.max(4, 4 + payments.size());
        StringBuilder b = sheetBegin("A1:F" + last, new double[]{23,30,18,22,27,28}, 4);
        titleRows(b, "HISTÓRICO DE PAGAMENTOS", "Tudo que foi registrado como pago no aplicativo", 6);
        headerRow(b, h);
        int r=5;
        for (JSONObject p : payments) {
            rowStart(b, r, 22);
            addText(b, "A"+r, formatDateTime(p.optString("paidAt", "")), 4);
            addText(b, "B"+r, p.optString("clientName", ""), 4);
            addNumber(b, "C"+r, safeNumber(p.optDouble("value", 0)), 5);
            String due = p.optString("dueDate", "");
            if (due.isEmpty()) addText(b, "D"+r, "—", 4); else addDate(b, "D"+r, due, 6);
            addText(b, "E"+r, p.optString("clientId", ""), 4);
            addText(b, "F"+r, p.optString("id", ""), 4);
            rowEnd(b); r++;
        }
        finishTableSheet(b, "A4:F" + last, "A1:F1", "A2:F2");
        return b.toString();
    }

    private static String buildNotesSheet(List<JSONObject> notes) {
        final String[] h = {"TÍTULO", "CLIENTE", "ANOTAÇÃO", "ATUALIZADA EM", "CRIADA EM", "CÓDIGO"};
        int last = Math.max(4, 4 + notes.size());
        StringBuilder b = sheetBegin("A1:F" + last, new double[]{28,28,54,22,22,28}, 4);
        titleRows(b, "ANOTAÇÕES", "Lembretes e observações registrados no aplicativo", 6);
        headerRow(b, h);
        int r=5;
        for (JSONObject n : notes) {
            rowStart(b, r, 34);
            addText(b, "A"+r, n.optString("title", ""), 4);
            addText(b, "B"+r, n.optString("clientName", ""), 4);
            addText(b, "C"+r, n.optString("text", ""), 14);
            addText(b, "D"+r, formatDateTime(n.optString("updatedAt", "")), 4);
            addText(b, "E"+r, formatDateTime(n.optString("createdAt", "")), 4);
            addText(b, "F"+r, n.optString("id", ""), 4);
            rowEnd(b); r++;
        }
        finishTableSheet(b, "A4:F" + last, "A1:F1", "A2:F2");
        return b.toString();
    }

    private static String buildOpenSheet(List<JSONObject> open) {
        final String[] h = {"CLIENTE", "TELEFONE", "VALOR", "VENCIMENTO", "SITUAÇÃO", "RECORRÊNCIA", "ÚLTIMO PAGAMENTO", "OBSERVAÇÃO"};
        int last = Math.max(4, 4 + open.size());
        StringBuilder b = sheetBegin("A1:H" + last, new double[]{29,20,16,18,22,19,21,42}, 4);
        titleRows(b, "COBRANÇAS EM ABERTO", "Lista organizada por vencimento para conferir quem precisa pagar", 8);
        headerRow(b, h);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int r=5;
        for (JSONObject c : open) {
            String due = c.optString("dueDate", "");
            String status = clientStatus(c, today);
            rowStart(b, r, 24);
            addText(b, "A"+r, c.optString("name", ""), 4);
            addText(b, "B"+r, formatPhone(c.optString("phone", "")), 4);
            addNumber(b, "C"+r, safeNumber(c.optDouble("value", 0)), 5);
            if (due.isEmpty()) addText(b, "D"+r, "—", 4); else addDate(b, "D"+r, due, 6);
            addText(b, "E"+r, status, statusStyle(status));
            addText(b, "F"+r, recurrenceLabel(c), 4);
            addText(b, "G"+r, formatDateTime(c.optString("lastPaidAt", "")), 4);
            addText(b, "H"+r, c.optString("notes", ""), 14);
            rowEnd(b); r++;
        }
        finishTableSheet(b, "A4:H" + last, "A1:H1", "A2:H2");
        return b.toString();
    }

    private static String clientStatus(JSONObject c, String today) {
        if (c.optBoolean("closedPaid", false)) return "PAGO / ENCERRADO";
        String due = c.optString("dueDate", "");
        if (!due.isEmpty() && due.compareTo(today) < 0) return "ATRASADO";
        if (due.equals(today)) return "VENCE HOJE";
        if (!c.optString("lastPaidAt", "").isEmpty()) return "PAGO • PRÓXIMA AGENDADA";
        return "AGENDADO";
    }

    private static int statusStyle(String s) {
        if (s.startsWith("ATRASADO")) return 7;
        if (s.startsWith("PAGO")) return 8;
        if (s.startsWith("VENCE")) return 9;
        return 10;
    }

    private static String recurrenceLabel(JSONObject c) {
        String r = c.optString("recurrence", "monthly");
        if ("weekly".equals(r)) return "7 em 7 dias";
        if ("biweekly".equals(r)) return "15 em 15 dias";
        if ("once".equals(r)) return "Cobrança única";
        if ("custom".equals(r)) return "A cada " + Math.max(1, c.optInt("customDays", 1)) + " dias";
        return "Mensal";
    }

    private static StringBuilder sheetBegin(String dimension, double[] widths, int frozenRows) {
        StringBuilder b = new StringBuilder(8192);
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        b.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        b.append("<dimension ref=\"").append(dimension).append("\"/>");
        b.append("<sheetViews><sheetView workbookViewId=\"0\">");
        if (frozenRows > 0) b.append("<pane ySplit=\"").append(frozenRows).append("\" topLeftCell=\"A").append(frozenRows+1).append("\" activePane=\"bottomLeft\" state=\"frozen\"/>");
        b.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"18\"/>");
        b.append("<cols>");
        for (int i=0;i<widths.length;i++) b.append("<col min=\"").append(i+1).append("\" max=\"").append(i+1).append("\" width=\"").append(widths[i]).append("\" customWidth=\"1\"/>");
        b.append("</cols><sheetData>");
        return b;
    }

    private static void titleRows(StringBuilder b, String title, String subtitle, int cols) {
        rowStart(b,1,32); addText(b,"A1","ALEX PAGAMENTOS • " + title,1); rowEnd(b);
        rowStart(b,2,22); addText(b,"A2",subtitle + " • exportado em " + new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date()),2); rowEnd(b);
        // Linha 3 fica vazia para separar visualmente o título da tabela.
    }

    private static void headerRow(StringBuilder b, String[] headers) {
        rowStart(b,4,26);
        for (int i=0;i<headers.length;i++) addText(b, col(i+1)+"4", headers[i], 3);
        rowEnd(b);
    }

    private static void finishTableSheet(StringBuilder b, String filterRef, String merge1, String merge2) {
        b.append("</sheetData><autoFilter ref=\"").append(filterRef).append("\"/>");
        b.append("<mergeCells count=\"2\"><mergeCell ref=\"").append(merge1).append("\"/><mergeCell ref=\"").append(merge2).append("\"/></mergeCells>");
        b.append(pageMargins()).append("</worksheet>");
    }

    private static String pageMargins() {
        return "<pageMargins left=\"0.35\" right=\"0.35\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/>";
    }

    private static void rowStart(StringBuilder b, int r, double height) {
        b.append("<row r=\"").append(r).append("\" ht=\"").append(height).append("\" customHeight=\"1\">");
    }

    private static void rowEnd(StringBuilder b) { b.append("</row>"); }

    private static void addText(StringBuilder b, String ref, String value, int style) {
        String v = cleanExcelText(value);
        b.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                .append(xml(v)).append("</t></is></c>");
    }

    private static void addNumber(StringBuilder b, String ref, double value, int style) {
        double safe = safeNumber(value);
        b.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\" t=\"n\"><v>")
                .append(Double.toString(safe)).append("</v></c>");
    }

    private static void addDate(StringBuilder b, String ref, String iso, int style) {
        double serial = excelDateSerial(iso);
        if (serial <= 0) addText(b, ref, iso, 4);
        else addNumber(b, ref, serial, style);
    }

    private static double excelDateSerial(String iso) {
        try {
            String v = iso == null ? "" : iso.trim();
            if (v.length() >= 10) v = v.substring(0,10);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setLenient(false); f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(v);
            return (d.getTime() / 86400000d) + 25569d;
        } catch (Exception e) { return -1; }
    }

    private static double safeNumber(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? 0 : v; }

    private static String formatDateTime(String iso) {
        if (iso == null || iso.trim().isEmpty() || "null".equalsIgnoreCase(iso)) return "—";
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX"};
        for (String p : patterns) {
            try {
                SimpleDateFormat in = new SimpleDateFormat(p, Locale.US);
                in.setLenient(false); in.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = in.parse(iso);
                return new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(d);
            } catch (Exception ignored) {}
        }
        return iso.length() > 19 ? iso.substring(0,19).replace('T',' ') : iso.replace('T',' ');
    }

    private static String col(int n) {
        StringBuilder s = new StringBuilder();
        while (n > 0) { int r=(n-1)%26; s.insert(0,(char)('A'+r)); n=(n-1)/26; }
        return s.toString();
    }

    private static String cleanExcelText(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(Math.min(s.length(), 32767));
        for (int i=0; i<s.length() && out.length()<32767;) {
            int cp = s.codePointAt(i); i += Character.charCount(cp);
            boolean valid = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
            if (valid) out.appendCodePoint(cp);
        }
        if (out.length() > 32767) out.setLength(32767);
        return out.toString();
    }

    private static String xml(String s) {
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
    }

    private static void zipText(ZipOutputStream z, String path, String content) throws Exception {
        z.putNextEntry(new ZipEntry(path));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String contentTypesXml(int sheetCount) {
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/><Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/><Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>");
        for(int i=1;i<=sheetCount;i++) b.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return b.append("</Types>").toString();
    }

    private static String rootRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/></Relationships>";
    }

    private static String workbookXml(String[] names) {
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView xWindow=\"0\" yWindow=\"0\" windowWidth=\"24000\" windowHeight=\"12000\"/></bookViews><sheets>");
        for(int i=0;i<names.length;i++) b.append("<sheet name=\"").append(xml(names[i])).append("\" sheetId=\"").append(i+1).append("\" r:id=\"rId").append(i+1).append("\"/>");
        return b.append("</sheets><calcPr calcId=\"191029\"/></workbook>").toString();
    }

    private static String workbookRelsXml(int count) {
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for(int i=1;i<=count;i++) b.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        b.append("<Relationship Id=\"rId").append(count+1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
        return b.toString();
    }

    private static String appXml(String[] names) {
        StringBuilder titles=new StringBuilder(); for(String n:names) titles.append("<vt:lpstr>").append(xml(n)).append("</vt:lpstr>");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><Application>ALEX PAGAMENTOS</Application><DocSecurity>0</DocSecurity><ScaleCrop>false</ScaleCrop><HeadingPairs><vt:vector size=\"2\" baseType=\"variant\"><vt:variant><vt:lpstr>Planilhas</vt:lpstr></vt:variant><vt:variant><vt:i4>"+names.length+"</vt:i4></vt:variant></vt:vector></HeadingPairs><TitlesOfParts><vt:vector size=\""+names.length+"\" baseType=\"lpstr\">"+titles+"</vt:vector></TitlesOfParts><Company></Company><LinksUpToDate>false</LinksUpToDate><SharedDoc>false</SharedDoc><HyperlinksChanged>false</HyperlinksChanged><AppVersion>1.5</AppVersion></Properties>";
    }

    private static String coreXml() {
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); String now=f.format(new Date());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>ALEX PAGAMENTOS - Planilha de clientes</dc:title><dc:creator>ALEX PAGAMENTOS</dc:creator><cp:lastModifiedBy>ALEX PAGAMENTOS</cp:lastModifiedBy><dcterms:created xsi:type=\"dcterms:W3CDTF\">"+now+"</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">"+now+"</dcterms:modified></cp:coreProperties>";
    }

    private static String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"+
                "<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"[$R$-pt-BR] #,##0.00\"/><numFmt numFmtId=\"165\" formatCode=\"dd/mm/yyyy\"/></numFmts>"+
                "<fonts count=\"10\">"+
                font("11",false,"FF111827")+font("18",true,"FFFFFFFF")+font("10",false,"FFB9C1CD")+font("10",true,"FFFFFFFF")+font("10",true,"FF1F2937")+font("10",true,"FFB91C1C")+font("10",true,"FF166534")+font("10",true,"FF92400E")+font("10",false,"FF374151")+font("16",true,"FF0F172A")+
                "</fonts>"+
                "<fills count=\"9\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"+
                fill("FF171C24")+fill("FF293240")+fill("FFF8FAFC")+fill("FFFEE2E2")+fill("FFDCFCE7")+fill("FFFEF3C7")+fill("FFE0F2FE")+"</fills>"+
                "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style=\"thin\"><color rgb=\"FFD9E0E7\"/></left><right style=\"thin\"><color rgb=\"FFD9E0E7\"/></right><top style=\"thin\"><color rgb=\"FFD9E0E7\"/></top><bottom style=\"thin\"><color rgb=\"FFD9E0E7\"/></bottom><diagonal/></border></borders>"+
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"+
                "<cellXfs count=\"15\">"+
                xf(0,0,0,0,false,false,false,"",false)+
                xf(0,1,2,0,true,true,false,"left",false)+
                xf(0,2,2,0,true,true,false,"left",false)+
                xf(0,3,3,1,true,true,true,"center",true)+
                xf(0,0,0,1,false,false,true,"left",false)+
                xf(164,0,0,1,false,false,true,"right",false)+
                xf(165,0,0,1,false,false,true,"center",false)+
                xf(0,5,5,1,true,true,true,"center",true)+
                xf(0,6,6,1,true,true,true,"center",true)+
                xf(0,7,7,1,true,true,true,"center",true)+
                xf(0,8,4,1,true,true,true,"center",true)+
                xf(0,4,8,1,true,true,true,"left",false)+
                xf(0,9,8,1,true,true,true,"right",false)+
                xf(164,9,8,1,true,true,true,"right",false)+
                xf(0,0,0,1,false,false,true,"left",true)+
                "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles><dxfs count=\"0\"/><tableStyles count=\"0\" defaultTableStyle=\"TableStyleMedium2\" defaultPivotStyle=\"PivotStyleLight16\"/></styleSheet>";
    }

    private static String font(String size, boolean bold, String color) {
        return "<font>"+(bold?"<b/>":"")+"<sz val=\""+size+"\"/><color rgb=\""+color+"\"/><name val=\"Calibri\"/><family val=\"2\"/><scheme val=\"minor\"/></font>";
    }

    private static String fill(String color) {
        return "<fill><patternFill patternType=\"solid\"><fgColor rgb=\""+color+"\"/><bgColor indexed=\"64\"/></patternFill></fill>";
    }

    private static String xf(int numFmt, int font, int fill, int border, boolean applyFont, boolean applyFill, boolean applyAlign, String align, boolean wrap) {
        StringBuilder b=new StringBuilder("<xf numFmtId=\"").append(numFmt).append("\" fontId=\"").append(font).append("\" fillId=\"").append(fill).append("\" borderId=\"").append(border).append("\" xfId=\"0\"");
        if(numFmt!=0)b.append(" applyNumberFormat=\"1\""); if(applyFont)b.append(" applyFont=\"1\""); if(applyFill)b.append(" applyFill=\"1\""); if(border!=0)b.append(" applyBorder=\"1\""); if(applyAlign)b.append(" applyAlignment=\"1\"");
        if(applyAlign){b.append("><alignment vertical=\"center\""); if(!align.isEmpty())b.append(" horizontal=\"").append(align).append("\""); if(wrap)b.append(" wrapText=\"1\""); b.append("/></xf>");} else b.append("/>");
        return b.toString();
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
    private static String readValidJson(File f) {
        try {
            if (f == null || !f.exists() || f.length() <= 0) return null;
            String json = read(f);
            new JSONObject(json);
            return json;
        } catch (Exception e) { return null; }
    }

    private static String read(File f)throws Exception{try(FileInputStream in=new FileInputStream(f)){byte[] b=new byte[(int)f.length()];int o=0,r;while(o<b.length&&(r=in.read(b,o,b.length-o))>0)o+=r;return new String(b,0,o, StandardCharsets.UTF_8);}}
    private static void write(File f,String s)throws Exception{try(FileOutputStream o=new FileOutputStream(f,false)){o.write(s.getBytes(StandardCharsets.UTF_8));o.flush();o.getFD().sync();}}
    private static void copy(File a,File b)throws Exception{try(FileInputStream i=new FileInputStream(a);FileOutputStream o=new FileOutputStream(b)){byte[] x=new byte[8192];int n;while((n=i.read(x))>0)o.write(x,0,n);o.flush();o.getFD().sync();}}
}
