package cc.wechat.observatory.wechat;

/** Confirms a queued request without preparing or submitting another message. */
public final class QueuedMessageConfirmation {
    private QueuedMessageConfirmation() { }

    public static SendResult await(long chatRecordId,
                                   LocalMessageConfirmation.StatusReader reader,
                                   LocalMessageConfirmation.Sleeper sleeper,
                                   int maxChecks, long delayMillis) {
        try {
            LocalMessageConfirmation.Result result = LocalMessageConfirmation.awaitTerminal(
                    reader, sleeper, maxChecks, delayMillis);
            if (result == LocalMessageConfirmation.Result.SENT) {
                return SendResult.sent(chatRecordId);
            }
            if (result == LocalMessageConfirmation.Result.FAILED) {
                return SendResult.failed(chatRecordId,
                        "WeChat local message marked failed; local msg id=" + chatRecordId);
            }
            return unconfirmed(chatRecordId, "local status did not reach a terminal state");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unconfirmed(chatRecordId, "confirmation interrupted");
        } catch (Exception e) {
            // The request is already queued. Retain its id even if the account
            // database becomes unavailable while we are observing its status.
            return unconfirmed(chatRecordId, "local status unavailable (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static SendResult unconfirmed(long chatRecordId, String reason) {
        return SendResult.failed(chatRecordId,
                "WeChat message queued but delivery unconfirmed: " + reason
                        + "; local msg id=" + chatRecordId + "; message was not resubmitted");
    }
}
