package cc.wechat.observatory.config;

import java.util.Properties;

/** Ordered, lazy lookup: a readable live source wins over stale disk mirrors. */
final class ConfigSources {
    interface Source {
        Properties read();
    }

    private ConfigSources() { }

    static Properties firstAvailable(Source... sources) {
        for (Source source : sources) {
            Properties values = source.read();
            if (values != null && !values.isEmpty()) {
                return values;
            }
        }
        return new Properties();
    }
}
