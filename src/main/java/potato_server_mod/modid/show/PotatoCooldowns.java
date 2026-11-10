package potato_server_mod.modid.show;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Monotonic, per-player cooldowns, kept across disconnects during this server session. */
public final class PotatoCooldowns {
    private static final long DURATION_NANOS = TimeUnit.MINUTES.toNanos(1);
    private final Map<UUID, Long> startedAt = new HashMap<>();
    private final LongSupplier clock;

    public PotatoCooldowns(LongSupplier clock) {
        this.clock = clock;
    }

    public long remainingSeconds(UUID player) {
        Long start = startedAt.get(player);
        if (start == null) return 0;
        long remaining = DURATION_NANOS - (clock.getAsLong() - start);
        return remaining <= 0 ? 0 : 1 + (remaining - 1) / TimeUnit.SECONDS.toNanos(1);
    }

    public boolean tryAcquire(UUID player) {
        if (remainingSeconds(player) > 0) return false;
        startedAt.put(player, clock.getAsLong());
        return true;
    }

    public void clearExpired() {
        long now = clock.getAsLong();
        startedAt.values().removeIf(start -> now - start >= DURATION_NANOS);
    }
}
