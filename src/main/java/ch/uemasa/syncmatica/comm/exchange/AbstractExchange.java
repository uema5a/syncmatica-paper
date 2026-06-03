package ch.uemasa.syncmatica.comm.exchange;

import ch.uemasa.syncmatica.SyncmaticaContext;
import ch.uemasa.syncmatica.comm.ExchangeTarget;
import ch.uemasa.syncmatica.net.SyncByteBuf;

import java.util.UUID;

/**
 * Common exchange state. A last-activity timestamp lets an external sweeper reap exchanges that stopped
 * making progress (see {@link #isStale(long)} / {@link #touch()}).
 */
public abstract class AbstractExchange implements Exchange {

    protected final ExchangeTarget partner;
    protected final SyncmaticaContext context;
    private volatile long lastActivityMillis;
    private boolean finished;
    private boolean success;

    protected AbstractExchange(ExchangeTarget partner, SyncmaticaContext context) {
        this.partner = partner;
        this.context = context;
        this.lastActivityMillis = System.currentTimeMillis();
    }

    /** Records progress so the sweeper's idle clock resets; an actively-progressing transfer is never reaped. */
    public void touch() {
        this.lastActivityMillis = System.currentTimeMillis();
    }

    /** @return true if no activity has occurred for more than {@code timeoutMillis}. */
    public boolean isStale(long timeoutMillis) {
        return System.currentTimeMillis() - lastActivityMillis > timeoutMillis;
    }

    @Override
    public ExchangeTarget getPartner() {
        return partner;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public boolean isSuccessful() {
        return success;
    }

    protected void succeed() {
        finished = true;
        success = true;
        onClose();
    }

    @Override
    public void close(boolean notifyPartner) {
        finished = true;
        success = false;
        if (notifyPartner) {
            sendCancelPacket();
        }
        onClose();
    }

    /** Hook for post-completion cleanup (close streams, release locks, fire side effects). */
    protected void onClose() {
    }

    /** Emit the appropriate cancel/deny packet; overridden by exchanges that support cancellation. */
    protected void sendCancelPacket() {
    }

    /** Reads the leading UUID of a body without affecting the caller's reader. */
    protected static UUID peekUUID(byte[] body) {
        if (body.length < 16) {
            return null;
        }
        return new SyncByteBuf(body).readUUID();
    }
}
