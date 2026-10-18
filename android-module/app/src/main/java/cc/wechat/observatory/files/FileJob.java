package cc.wechat.observatory.files;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Version 1 deliberately excludes formats whose native thumbnail task also reads the source. */
public final class FileJob {
    public static final long MAX_BYTES = 300L * 1024 * 1024;
    private static final Set<String> THUMBNAILS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "bmp", "png", "tif", "tiff", "heic", "heif", "webp",
            "avi", "flv", "m4v", "mkv", "mov", "mp4", "mts", "webm", "wmv"));
    public final long id, size;
    public final String owner, device, recipient, token, attachmentId, name, sha256;

    public FileJob(JSONObject item) throws Exception {
        id = item.getLong("id");
        owner = required(item, "owner_wxid", 256);
        device = required(item, "device", 256);
        recipient = required(item, "wxid", 256);
        token = required(item, "lease_token", 256);
        if (id <= 0 || !"file".equals(item.getString("kind"))) throw new IllegalArgumentException("Invalid file job");
        JSONObject attachment = item.getJSONObject("attachment");
        attachmentId = required(attachment, "id", 128);
        name = safeName(attachment.getString("name"));
        size = attachment.getLong("size");
        sha256 = attachment.getString("sha256");
        if (size <= 0 || size > MAX_BYTES || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid attachment length or SHA256");
    }

    /** Keep small files at 120s; larger transfers allow 256 KiB/s, capped at 20 minutes.
     * Used independently for downloading and observing native upload completion. */
    static long transferTimeoutMillis(long size) {
        if (size <= 0 || size > MAX_BYTES) throw new IllegalArgumentException("Invalid attachment length");
        long seconds = (size + 256L * 1024 - 1) / (256L * 1024);
        return Math.max(120000L, Math.min(20L * 60 * 1000, seconds * 1000));
    }

    public static String safeName(String value) {
        if (value == null || value.isEmpty() || value.equals(".") || value.equals("..")
                || value.getBytes(StandardCharsets.UTF_8).length > 240 || isSpace(value.codePointAt(0))
                || isSpace(value.codePointBefore(value.length())))
            throw new IllegalArgumentException("Invalid file name");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == '/' || c == '\\'
                    || (Character.isSurrogate(c) && !(Character.isHighSurrogate(c)
                    && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(++i)))))
                throw new IllegalArgumentException("Invalid file name");
        }
        int dot = value.lastIndexOf('.');
        String extension = dot < 0 ? "" : value.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (THUMBNAILS.contains(extension)) throw new IllegalArgumentException("Image/video attachments are not supported by file protocol 1");
        return value;
    }

    private static boolean isSpace(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }

    private static String required(JSONObject object, String key, int max) throws Exception {
        String value = object.getString(key);
        if (value.isEmpty() || value.length() > max || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid " + key);
        return value;
    }

    public static String digest(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
