package ch.uemasa.syncmatica.config;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-uploader byte-quota accounting. Counters are in-memory and reset each server start.
 */
public final class QuotaService {

    private final boolean enabled;
    private final long limit;
    private final Map<String, Long> progress = new HashMap<>();

    public QuotaService(boolean enabled, long limit) {
        this.enabled = enabled;
        this.limit = limit;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isOverQuota(String uploaderName, long additional) {
        if (!enabled) {
            return false;
        }
        if (additional <= 0) {
            return false;
        }
        // Saturating add so a near-Long.MAX value can't wrap negative and slip under the limit.
        long current = progress.getOrDefault(uploaderName, 0L);
        long total = saturatingAdd(current, additional);
        return total > limit;
    }

    public void addProgress(String uploaderName, long additional) {
        if (!enabled) {
            return;
        }
        if (additional <= 0) {
            return;
        }
        progress.merge(uploaderName, additional, QuotaService::saturatingAdd);
    }

    /** Adds two non-negative longs, clamping at {@link Long#MAX_VALUE} instead of overflowing. */
    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        // Inputs are non-negative, so a negative sum means overflow.
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
