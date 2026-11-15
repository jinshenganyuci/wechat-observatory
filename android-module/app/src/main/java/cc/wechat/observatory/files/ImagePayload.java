package cc.wechat.observatory.files;

import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Shared image boundary: decode bounds only, before WeChat or a vision client sees bytes. */
public final class ImagePayload {
    public static final long MAX_BYTES = 8L * 1024 * 1024;
    private ImagePayload() { }

    public static String mime(byte[] bytes) throws IOException {
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216
                && (bytes[2] & 255) == 255) return "image/jpeg";
        if (bytes.length >= 8 && (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78
                && bytes[3] == 71 && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10)
            return "image/png";
        throw new IOException("Only actual JPEG and PNG images are supported");
    }
    public static void validate(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Image exceeds 8 MiB");
        String expected = mime(bytes);
        completeTail(bytes, bytes.length, expected);
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        bounds(options, expected);
    }
    public static void validate(File file) throws IOException {
        if (file.length() == 0 || file.length() > MAX_BYTES) throw new IOException("Image exceeds 8 MiB");
        byte[] header = new byte[8];
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.read(header) != header.length) throw new IOException("Incomplete image header");
        }
        String expected = mime(header);
        byte[] tail = new byte[12];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < tail.length) throw new IOException("Incomplete image");
            input.seek(input.length() - tail.length); input.readFully(tail);
        }
        completeTail(tail, tail.length, expected);
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        bounds(options, expected);
    }
    private static void completeTail(byte[] bytes, int length, String mime) throws IOException {
        if ("image/jpeg".equals(mime)) {
            if (length >= 2 && (bytes[length - 2] & 255) == 255 && (bytes[length - 1] & 255) == 217) return;
        } else if (length >= 12) {
            byte[] end = {0, 0, 0, 0, 73, 69, 78, 68, (byte) 174, 66, 96, (byte) 130};
            boolean same = true;
            for (int i = 0; i < end.length; i++) same &= bytes[length - 12 + i] == end[i];
            if (same) return;
        }
        throw new IOException("Incomplete image payload");
    }
    private static void bounds(BitmapFactory.Options options, String expected) throws IOException {
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 16384
                || options.outHeight > 16384 || (long) options.outWidth * options.outHeight > 32000000L
                || !expected.equals(options.outMimeType)) throw new IOException("Invalid or oversized image dimensions");
    }
}
