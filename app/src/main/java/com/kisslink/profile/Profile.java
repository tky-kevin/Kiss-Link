package com.kisslink.profile;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 個人名片資料模型——姓名 + 可自訂的聯絡欄位（電話／Email／公司…皆為自訂欄位）。
 * 頭像不存在這裡（由 {@link ProfileStore} 以檔案管理），這個物件只負責可序列化的文字資料。
 */
public final class Profile {

    /** 單一聯絡欄位（label 自訂，例如「電話」「Email」「公司」）。 */
    public static final class Field {
        public String label;
        public String value;
        public Field(String label, String value) { this.label = label; this.value = value; }
    }

    @NonNull public String name;
    @NonNull public final List<Field> fields;

    public Profile(@NonNull String name, @NonNull List<Field> fields) {
        this.name = name;
        this.fields = fields;
    }

    public static Profile empty() {
        return new Profile("", new ArrayList<>());
    }

    public boolean isBlank() {
        return name.trim().isEmpty() && fields.isEmpty();
    }

    /**
     * 組成 vCard 3.0（可被系統聯絡人 App 匯入）。欄位 label 對應到常見 vCard 屬性，
     * 認不得的就放進備註，確保不漏資料。
     */
    @NonNull
    public byte[] toVCard() {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCARD\r\n");
        sb.append("VERSION:3.0\r\n");
        String fn = name.trim().isEmpty() ? "KissLink Contact" : name.trim();
        sb.append("FN:").append(escape(fn)).append("\r\n");
        sb.append("N:").append(escape(fn)).append(";;;;\r\n");
        List<String> notes = new ArrayList<>();
        for (Field f : fields) {
            if (f.value == null || f.value.trim().isEmpty()) continue;
            String key = f.label == null ? "" : f.label.trim().toLowerCase();
            String v = escape(f.value.trim());
            if (key.contains("phone") || key.contains("電話") || key.contains("手機") || key.contains("tel")) {
                sb.append("TEL;TYPE=CELL:").append(v).append("\r\n");
            } else if (key.contains("mail") || key.contains("信箱") || key.contains("郵")) {
                sb.append("EMAIL:").append(v).append("\r\n");
            } else if (key.contains("公司") || key.contains("org") || key.contains("company")) {
                sb.append("ORG:").append(v).append("\r\n");
            } else if (key.contains("title") || key.contains("職") ) {
                sb.append("TITLE:").append(v).append("\r\n");
            } else if (key.contains("url") || key.contains("網") || key.contains("web")) {
                sb.append("URL:").append(v).append("\r\n");
            } else if (key.contains("addr") || key.contains("地址")) {
                sb.append("ADR:;;").append(v).append(";;;;\r\n");
            } else {
                notes.add((f.label == null ? "" : f.label.trim() + ": ") + f.value.trim());
            }
        }
        if (!notes.isEmpty()) {
            sb.append("NOTE:").append(escape(String.join(" | ", notes))).append("\r\n");
        }
        sb.append("END:VCARD\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    /** 解析 vCard（收到名片時用），抽出姓名與常見欄位。 */
    @NonNull
    public static Profile fromVCard(@NonNull byte[] vcf) {
        Profile p = Profile.empty();
        String text = new String(vcf, StandardCharsets.UTF_8);
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).toUpperCase();
            String val = unescape(line.substring(colon + 1).trim());
            if (val.isEmpty()) continue;
            if (key.equals("FN")) {
                p.name = val;
            } else if (key.startsWith("TEL")) {
                p.fields.add(new Field("電話", val));
            } else if (key.startsWith("EMAIL")) {
                p.fields.add(new Field("Email", val));
            } else if (key.startsWith("ORG")) {
                p.fields.add(new Field("公司", val.replace(";", " ").trim()));
            } else if (key.startsWith("TITLE")) {
                p.fields.add(new Field("職稱", val));
            } else if (key.startsWith("URL")) {
                p.fields.add(new Field("網址", val));
            } else if (key.startsWith("ADR")) {
                String adr = val.replace(";", " ").trim();
                if (!adr.isEmpty()) p.fields.add(new Field("地址", adr));
            } else if (key.startsWith("NOTE")) {
                for (String part : val.split("\\|")) {
                    String s = part.trim();
                    if (s.isEmpty()) continue;
                    int sep = s.indexOf(": ");
                    if (sep > 0) p.fields.add(new Field(s.substring(0, sep), s.substring(sep + 2)));
                    else p.fields.add(new Field("備註", s));
                }
            }
        }
        return p;
    }

    // ── byte[] 不可變回傳輔助 ──
    public static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
