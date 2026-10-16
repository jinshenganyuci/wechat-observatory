package cc.wechat.observatory.wechat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Replays a completed send result after an ACK is lost, within this process only. */
public final class OutboxDeliveryCache {
    private final Map<String, SendResult> completed;

    public OutboxDeliveryCache(final int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        completed = new LinkedHashMap<String, SendResult>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, SendResult> eldest) {
                return size() > capacity;
            }
        };
    }

    public static String scope(String endpoint, String apiKey, String device, String owner,
                               String installation, String accountSession) {
        return digest(endpoint, apiKey, device, owner, installation, accountSession);
    }

    public synchronized SendResult get(String scope, long id, String recipient, String text, String createdAt) {
        return completed.get(digest(scope, String.valueOf(id), recipient, text, createdAt));
    }

    public synchronized void remember(String scope, long id, String recipient, String text,
                                      String createdAt, SendResult result) {
        completed.put(digest(scope, String.valueOf(id), recipient, text, createdAt), result);
    }

    private static String digest(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
                // Length prefixes prevent ambiguity between adjacent identity fields.
                for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (bytes.length >>> shift));
                digest.update(bytes);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
