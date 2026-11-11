package app.signal.tracker;

import org.json.JSONArray;

/** Minimal DNS message reading: the question name and any IPv4 answers. */
final class Dns {
    static final class Question {
        final String name;
        final int end;
        Question(String name, int end) { this.name = name; this.end = end; }
    }

    private Dns() {}

    static Question question(byte[] m) {
        if (m.length < 12 || (((m[4] & 0xFF) << 8) | (m[5] & 0xFF)) < 1) return null;
        int[] end = new int[1];
        String name = readName(m, 12, end);
        return new Question(name, end[0] + 4);
    }

    static JSONArray ipv4Answers(byte[] m) {
        JSONArray ips = new JSONArray();
        try {
            Question q = question(m);
            if (q == null) return ips;
            int off = q.end;
            int count = ((m[6] & 0xFF) << 8) | (m[7] & 0xFF);
            int[] end = new int[1];
            for (int i = 0; i < count && off + 10 < m.length; i++) {
                readName(m, off, end);
                off = end[0];
                int type = ((m[off] & 0xFF) << 8) | (m[off + 1] & 0xFF);
                int len = ((m[off + 8] & 0xFF) << 8) | (m[off + 9] & 0xFF);
                if (type == 1 && len == 4) {
                    ips.put((m[off + 10] & 0xFF) + "." + (m[off + 11] & 0xFF) + "." + (m[off + 12] & 0xFF) + "." + (m[off + 13] & 0xFF));
                }
                off += 10 + len;
            }
        } catch (Exception ignored) {}
        return ips;
    }

    /** Reads a possibly compressed name; end[0] receives the offset just past it. */
    private static String readName(byte[] m, int off, int[] end) {
        StringBuilder sb = new StringBuilder();
        boolean jumped = false;
        end[0] = off;
        for (int guard = 0; off < m.length && guard < 128; guard++) {
            int len = m[off] & 0xFF;
            if (len == 0) { if (!jumped) end[0] = off + 1; break; }
            if ((len & 0xC0) == 0xC0) {
                if (!jumped) end[0] = off + 2;
                off = ((len & 0x3F) << 8) | (m[off + 1] & 0xFF);
                jumped = true;
                continue;
            }
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(m, off + 1, len, java.nio.charset.StandardCharsets.ISO_8859_1));
            off += len + 1;
        }
        return sb.toString().toLowerCase();
    }
}
