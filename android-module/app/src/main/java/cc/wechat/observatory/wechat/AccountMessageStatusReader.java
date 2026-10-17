package cc.wechat.observatory.wechat;

/** Reads only the database captured after a kernel identity check; never resolves another account. */
public final class AccountMessageStatusReader {
    public interface Scope { boolean current(); }
    public interface Database {
        String path(Object database);
        int status(Object database, long localId) throws Exception;
    }
    private final RuntimeAccount account;
    private final Object database;
    private final String path;
    private final Scope scope;
    private final Database reader;

    public AccountMessageStatusReader(RuntimeAccount account, Object database, Scope scope, Database reader) {
        this.account = account;
        this.database = database;
        this.scope = scope;
        this.reader = reader;
        path = database == null ? "" : RuntimeAccount.normalizePath(reader.path(database));
        if (database != null && !account.ownsDatabase(path)) {
            throw new IllegalArgumentException("Message database does not belong to the verified account");
        }
    }

    public int read(long localId) throws Exception {
        if (database == null || localId <= 0 || !scope.current()
                || !path.equals(RuntimeAccount.normalizePath(reader.path(database))) || !account.ownsDatabase(path)) {
            return LocalMessageConfirmation.STATUS_UNKNOWN;
        }
        int status = reader.status(database, localId);
        return scope.current() ? status : LocalMessageConfirmation.STATUS_UNKNOWN;
    }
}
