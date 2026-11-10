package potato_server_mod.modid.show;

/** A five-second show at the normal 20 ticks per second, with no queued tasks. */
public final class PotatoTimeline {
    public static final int DURATION_TICKS = 100;
    private static final Cue NONE = new Cue(false, false, false, 0, false);
    private int elapsedTicks;
    private boolean cancelled;

    public record Cue(boolean smoke, boolean alarm, boolean explosion, int countdown, boolean finished) {}

    public Cue advance() {
        if (cancelled || elapsedTicks >= DURATION_TICKS) return NONE;
        int tick = ++elapsedTicks;
        if (tick == DURATION_TICKS) return new Cue(false, false, false, 0, true);

        boolean smoke = tick % 4 == 0;
        boolean alarm = tick == 20 || tick == 40 || tick == 60;
        boolean explosion = tick == 80;
        int countdown = tick >= 40 && tick % 20 == 0 ? (DURATION_TICKS - tick) / 20 : 0;
        return new Cue(smoke, alarm, explosion, countdown, false);
    }

    public void cancel() {
        cancelled = true;
    }
}
