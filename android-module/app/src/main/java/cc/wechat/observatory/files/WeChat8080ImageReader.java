package cc.wechat.observatory.files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;

/** Reads only this message's already cached native image; never starts a download. */
public final class WeChat8080ImageReader {
    private final Class<?> messages, images, vfs;
    private final Method openInput;
    private String lastFailure = "";

    public WeChat8080ImageReader(ClassLoader loader, String versionName, long versionCode) throws Exception {
        if (!"8.0.80".equals(versionName) || versionCode != 3220) {
            throw new UnsupportedOperationException("Unverified WeChat image reader " + versionName + "/" + versionCode);
        }
        messages = Class.forName("yx0.k0", false, loader);
        images = Class.forName("g61.a1", false, loader);
        vfs = Class.forName("com.tencent.mm.vfs.z6", false, loader);
        Class<?> message = Class.forName("com.tencent.mm.storage.f9", false, loader);
        Class<?> storage = Class.forName("g61.k0", false, loader);
        require(images.getDeclaredMethod("Hj"), storage);
        require(storage.getDeclaredMethod("M2", message), String.class);
        require(storage.getDeclaredMethod("i4", message, String.class, boolean.class), String.class);
        openInput = vfs.getDeclaredMethod("E", String.class);
        require(openInput, InputStream.class);
        require(vfs.getDeclaredMethod("j", String.class), boolean.class);
        require(message.getMethod("getMsgId"), long.class);
        require(message.getMethod("O0"), String.class);
        require(message.getMethod("A0"), int.class);
        require(message.getMethod("getType"), int.class);
        require(message.getMethod("z0"), String.class);
        messages.getDeclaredField("A1");
    }

    /** Caller checks that the native login is unchanged before and after reading. */
    public Image read(String talker, long messageId, int expectedDirection, long maxBytes) throws Exception {
        lastFailure = "";
        long limit = Math.min(ImagePayload.MAX_BYTES, maxBytes);
        if (limit <= 0) return null;
        Object message = XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(messages, "A1"),
                "k", talker, messageId);
        if (message == null || ((Number) XposedHelpers.callMethod(message, "getMsgId")).longValue() != messageId
                || !talker.equals(XposedHelpers.callMethod(message, "O0"))
                || ((Number) XposedHelpers.callMethod(message, "getType")).intValue() != 3
                || ((Number) XposedHelpers.callMethod(message, "A0")).intValue() != expectedDirection) return null;
        Object storage = XposedHelpers.callStaticMethod(images, "Hj");
        // Prefer an already cached full image. M2 never requests a download.
        Image full = readPath(XposedHelpers.callMethod(storage, "M2", message), messageId, limit, false);
        if (full != null) return full;
        // false keeps thumbnail resolution in read mode, without creating directories.
        String hint = (String) XposedHelpers.callMethod(message, "z0");
        Object thumbPath = XposedHelpers.callMethod(storage, "i4", message, hint, false);
        // Match WeChat 8.0.80 k0.i6: its chat UI checks the resolved thumbnail's
        // exact "hd" sibling with VFS.exists before selecting it. No search or write.
        if (thumbPath instanceof String && !((String) thumbPath).isEmpty()
                && !((String) thumbPath).endsWith("hd")) {
            String hd = thumbPath + "hd";
            if (Boolean.TRUE.equals(XposedHelpers.callStaticMethod(vfs, "j", hd))) {
                Image cached = readPath(hd, messageId, limit, true);
                if (cached != null) return cached;
            }
        }
        return readPath(thumbPath, messageId, limit, true);
    }

    private Image readPath(Object nativePath, long messageId, long limit, boolean thumbnail) throws Exception {
        String stage = thumbnail ? "thumbnail" : "full";
        if (!(nativePath instanceof String) || ((String) nativePath).isEmpty()) {
            failure(stage, "path_unavailable"); return null;
        }
        // The path comes only from WeChat's account-scoped message storage. Do not
        // accept a webhook path, traverse image folders, or alter the source file.
        InputStream opened;
        try {
            opened = (InputStream) openInput.invoke(null, nativePath);
        } catch (InvocationTargetException unavailable) {
            if (unavailable.getCause() instanceof IOException) { failure(stage, "file_unavailable"); return null; }
            throw unavailable;
        }
        try (InputStream input = opened) {
            if (input == null) { failure(stage, "stream_unavailable"); return null; }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if ((long) output.size() + count > limit) { failure(stage, "size_limit"); return null; }
                output.write(buffer, 0, count);
            }
            byte[] bytes = output.toByteArray();
            ImagePayload.validate(bytes);
            String mime = ImagePayload.mime(bytes);
            return new Image(bytes, mime, "image-" + messageId + (thumbnail ? "-thumb" : "")
                    + ("image/png".equals(mime) ? ".png" : ".jpg"), thumbnail);
        } catch (IOException unreadable) {
            // Missing, partial, encrypted, and unsupported cached files are not
            // relabelled as JPEG. The bounded caller can check again later.
            failure(stage, unreadable.getMessage());
            return null;
        }
    }

    public String lastFailure() { return lastFailure; }
    private void failure(String stage, String reason) {
        lastFailure += (lastFailure.isEmpty() ? "" : "; ") + stage + ":" + reason;
    }

    private static void require(Method method, Class<?> result) throws NoSuchMethodException {
        if (method.getReturnType() != result) throw new NoSuchMethodException("Native image reader binding changed");
    }

    public static final class Image {
        public final byte[] bytes;
        public final String mime, name;
        public final boolean thumbnail;
        Image(byte[] bytes, String mime, String name, boolean thumbnail) {
            this.bytes = bytes;
            this.mime = mime;
            this.name = name;
            this.thumbnail = thumbnail;
        }
    }
}
