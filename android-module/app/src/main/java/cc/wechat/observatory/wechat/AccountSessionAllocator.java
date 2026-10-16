package cc.wechat.observatory.wechat;

import java.util.UUID;

/**
 * Allocates account sessions from a previously reserved range, using memory only.
 * The module app owns the installation ID and persistent reservation high watermark.
 */
public final class AccountSessionAllocator {
    private final String instanceId;
    private final long lastInclusive;
    private long nextGeneration;
    private boolean exhausted;

    public AccountSessionAllocator(String instanceId, long firstInclusive, long lastInclusive) {
        if (instanceId == null || !instanceId.matches("[A-Za-z0-9_-]{16,64}")) {
            throw new IllegalArgumentException("Invalid installation ID");
        }
        if (firstInclusive <= 0 || lastInclusive < firstInclusive) {
            throw new IllegalArgumentException("Invalid account generation reservation");
        }
        this.instanceId = instanceId;
        this.nextGeneration = firstInclusive;
        this.lastInclusive = lastInclusive;
    }

    public synchronized AccountSession allocate() {
        if (exhausted) {
            throw new IllegalStateException("Account generation reservation exhausted; reload module configuration");
        }
        long generation = nextGeneration;
        if (generation == lastInclusive) {
            exhausted = true;
        } else {
            nextGeneration = generation + 1;
        }
        return new AccountSession(instanceId, UUID.randomUUID().toString(), generation);
    }

    /**
     * Calculates a fresh range without reading a clock or writing any files.
     * The caller must atomically persist lastInclusive before publishing this range,
     * and serialize reservation against every other load in the module process.
     */
    public static Range reserveAfter(long highWatermark, long blockSize) {
        if (highWatermark < 0 || blockSize <= 0) {
            throw new IllegalArgumentException("Invalid account generation reservation size");
        }
        if (highWatermark > Long.MAX_VALUE - blockSize) {
            throw new IllegalStateException("Account generation exhausted");
        }
        return new Range(highWatermark + 1, highWatermark + blockSize);
    }

    public static final class Range {
        public final long firstInclusive;
        public final long lastInclusive;

        private Range(long firstInclusive, long lastInclusive) {
            this.firstInclusive = firstInclusive;
            this.lastInclusive = lastInclusive;
        }
    }
}
