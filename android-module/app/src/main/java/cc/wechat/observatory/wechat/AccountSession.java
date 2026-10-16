package cc.wechat.observatory.wechat;

/** Allocated once per runtime binding, before any network operation. */
public final class AccountSession {
    public final String instanceId;
    public final String id;
    public final long generation;

    public AccountSession(String instanceId, String id, long generation) {
        if (instanceId == null || id == null || generation <= 0) {
            throw new IllegalArgumentException("Invalid account session");
        }
        this.instanceId = instanceId;
        this.id = id;
        this.generation = generation;
    }

    public boolean acceptsEcho(String session, long generation) {
        return id.equals(session) && this.generation == generation;
    }

}
