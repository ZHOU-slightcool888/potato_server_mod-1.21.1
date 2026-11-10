package potato_server_mod.modid.show;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Run with Java 21. A failing check fails Gradle's check/build task. */
public final class PotatoRegressionTest {
    public static void main(String[] args) throws Exception {
        timelineCompletesOnceWithBoundedEffects();
        cancelledAndIndependentShowsDoNotLeakEffects();
        cooldownUsesOneMinuteAndIndependentPlayers();
        preferencesDefaultOnAndSurviveReload();
        failedPreferenceSaveKeepsPreviousChoice();
        System.out.println("PASS: 5 potato server regression scenarios");
    }

    private static void timelineCompletesOnceWithBoundedEffects() {
        PotatoTimeline timeline = new PotatoTimeline();
        List<Integer> soundTicks = new ArrayList<>();
        List<Integer> countdown = new ArrayList<>();
        int particlePackets = 0;
        int particles = 0;
        int finishCount = 0;
        for (int tick = 1; tick <= 200; tick++) {
            PotatoTimeline.Cue cue = timeline.advance();
            if (cue.smoke()) {
                particlePackets += 2;
                particles += 11;
            }
            if (cue.explosion()) {
                particlePackets++;
                particles++;
            }
            if (cue.alarm() || cue.explosion()) soundTicks.add(tick);
            if (cue.countdown() > 0) countdown.add(cue.countdown());
            if (cue.finished()) {
                check(tick == 100, "disconnect must happen at 100 ticks, not before/after");
                finishCount++;
            }
            if (tick > 100) check(!cue.smoke() && !cue.alarm() && !cue.explosion(), "effects after completion");
        }
        check(finishCount == 1, "completion must fire exactly once");
        check(countdown.equals(List.of(3, 2, 1)), "countdown must be 3, 2, 1 in order");
        check(soundTicks.equals(List.of(20, 40, 60, 80)), "sound must not fire every tick");
        check(particlePackets == 49 && particles == 265, "particle budget changed");
    }

    private static void cancelledAndIndependentShowsDoNotLeakEffects() {
        PotatoTimeline first = new PotatoTimeline();
        PotatoTimeline second = new PotatoTimeline();
        for (int i = 0; i < 37; i++) first.advance();
        first.cancel();
        int finishes = 0;
        for (int i = 0; i < 120; i++) {
            PotatoTimeline.Cue cancelled = first.advance();
            check(!cancelled.smoke() && !cancelled.alarm() && !cancelled.explosion()
                    && cancelled.countdown() == 0 && !cancelled.finished(), "cancelled show still has effects");
            if (second.advance().finished()) finishes++;
        }
        check(finishes == 1, "another player's show must continue independently");
        // New sessions must not inherit the previous world's or player's elapsed time.
        PotatoTimeline fresh = new PotatoTimeline();
        for (int i = 1; i < 100; i++) check(!fresh.advance().finished(), "new show ended early");
        check(fresh.advance().finished(), "new show did not complete");
    }

    private static void cooldownUsesOneMinuteAndIndependentPlayers() {
        AtomicLong now = new AtomicLong(-500);
        PotatoCooldowns cooldowns = new PotatoCooldowns(now::get);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        check(cooldowns.tryAcquire(first), "first use should succeed");
        check(!cooldowns.tryAcquire(first), "immediate repeat must fail");
        check(cooldowns.tryAcquire(second), "cooldown must be per player");
        now.addAndGet(TimeUnit.SECONDS.toNanos(60) - 1);
        check(cooldowns.remainingSeconds(first) == 1, "round remaining time up");
        cooldowns.clearExpired();
        check(!cooldowns.tryAcquire(first), "one nanosecond before expiry must fail");
        now.incrementAndGet();
        cooldowns.clearExpired();
        check(cooldowns.remainingSeconds(first) == 0 && cooldowns.tryAcquire(first), "expiry must allow reuse");
        check(!cooldowns.tryAcquire(first), "successful reuse must start another cooldown");
    }

    private static void preferencesDefaultOnAndSurviveReload() throws Exception {
        Path directory = Files.createTempDirectory("potato-preferences-test-");
        try {
            Path file = directory.resolve("data/preferences.properties");
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            BlindnessPreferences preferences = new BlindnessPreferences(file);
            preferences.load();
            check(preferences.isEnabled(first), "default must be on");
            preferences.setEnabled(first, false);
            Files.writeString(file, "invalid-uuid=false\n", StandardOpenOption.APPEND);
            BlindnessPreferences reloaded = new BlindnessPreferences(file);
            reloaded.load();
            check(!reloaded.isEnabled(first), "opt out must survive reload");
            check(reloaded.isEnabled(second), "setting must not affect another player");
            reloaded.setEnabled(first, true);
            BlindnessPreferences restored = new BlindnessPreferences(file);
            restored.load();
            check(restored.isEnabled(first), "re-enable must survive reload");
        } finally {
            deleteTree(directory);
        }
    }

    private static void failedPreferenceSaveKeepsPreviousChoice() throws Exception {
        Path directory = Files.createTempDirectory("potato-save-failure-test-");
        try {
            Path blocked = directory.resolve("blocked");
            Files.writeString(blocked, "This is a file, not a directory.");
            BlindnessPreferences preferences = new BlindnessPreferences(blocked.resolve("preferences.properties"));
            UUID player = UUID.randomUUID();
            boolean failed = false;
            try {
                preferences.setEnabled(player, false);
            } catch (IOException expected) {
                failed = true;
            }
            check(failed && preferences.isEnabled(player), "failed save must not silently change setting");
        } finally {
            deleteTree(directory);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
