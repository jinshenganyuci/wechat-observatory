package cc.wechat.observatory;

/** Key names retained for the module UI and shell configuration command. No exported provider. */
final class BridgeConfigProvider {
    private BridgeConfigProvider() { }
    static final String[] CONFIG_KEYS = new String[]{
            "enabled",
            "bridge_url",
            "api_key",
            "poll_interval_ms",
            "poll_limit",
            "outbox_websocket_enabled",
            "contact_sync_interval_ms",
            "contact_sync_limit",
            "contact_include_chatrooms",
            "media_upload_enabled",
            "media_upload_limit_bytes",
            "stale_message_grace_ms",
            "stale_message_replay_limit"
    };

}
