package cc.wechat.observatory;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deletes only the named files used by older releases. Call after durable migration, off the UI thread. */
final class LegacyConfigCleanup {
    private static final String PACKAGE = "cc.wechat.observatory";
    private static final String[] PREFERENCES = {"bridge_config", "bridge_status"};
    private static boolean attempted;
    private static String lastReport;

    private LegacyConfigCleanup() { }

    /** Root is attempted only for an explicit user request, never during ordinary startup. */
    static synchronized String cleanup(Context context, boolean allowRoot) {
        if (context == null || !PACKAGE.equals(context.getPackageName())
                || context.getApplicationInfo().uid != android.os.Process.myUid()) {
            return "旧配置未清理：需要 Observatory 自身进程";
        }
        if (attempted && !allowRoot) return lastReport;

        File data = new File(context.getApplicationInfo().dataDir);
        Map<String, Target> targets = new LinkedHashMap<>();
        for (Target target : fixedTargets(data, Environment.getExternalStorageDirectory())) {
            targets.put(target.file.getAbsolutePath(), target);
        }
        boolean sharedDirectoryKnown = false;
        boolean preferenceDeletionFailed = false;
        for (String name : PREFERENCES) {
            File path = preferencePath(context, name);
            if (path != null) {
                File parent = path.getParentFile();
                if (parent != null && (PACKAGE.equals(parent.getName())
                        || "shared_prefs".equals(parent.getName()))) {
                    Target target = new Target(parent, name + ".xml", "旧共享配置 " + name);
                    Target backup = new Target(parent, name + ".xml.bak", "旧共享配置备份 " + name);
                    targets.put(target.file.getAbsolutePath(), target);
                    targets.put(backup.file.getAbsolutePath(), backup);
                    if (PACKAGE.equals(parent.getName())) sharedDirectoryKnown = true;
                }
            }
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    // This also evicts Android's cached preferences and follows LSPosed's
                    // getPreferencesDir hook. No getSharedPreferences()/Editor writes here.
                    if (!context.deleteSharedPreferences(name) && path == null) preferenceDeletionFailed = true;
                } catch (RuntimeException ignored) {
                    if (path == null) preferenceDeletionFailed = true;
                }
            }
        }

        List<Target> pending = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (Target target : targets.values()) {
            Removal result = remove(target, ANDROID_FILES);
            if (result == Removal.NEEDS_ACCESS) pending.add(target);
            if (result == Removal.REFUSED_PATH) refused.add(target.label);
        }
        String rootResult = "";
        if (allowRoot && !pending.isEmpty()) {
            rootResult = removeWithRoot(pending);
            if (rootResult.isEmpty()) pending.clear();
        }

        StringBuilder report = new StringBuilder();
        if (pending.isEmpty() && refused.isEmpty() && !preferenceDeletionFailed) {
            report.append("已清理可定位的旧配置文件");
        } else {
            report.append("旧配置清理未完成");
        }
        if (!pending.isEmpty()) {
            report.append(allowRoot ? "；Root 清理未完成：" : "；需要手动授权 Root 清理：");
            for (int i = 0; i < pending.size(); i++) {
                if (i > 0) report.append("、");
                report.append(pending.get(i).label);
            }
        }
        if (!rootResult.isEmpty()) report.append("（").append(rootResult).append("）");
        if (!refused.isEmpty()) report.append("；部分旧路径已改变，为避免误删已跳过");
        if (preferenceDeletionFailed) report.append("；系统偏好删除未确认，请重启 Observatory 后重试");
        if (!sharedDirectoryKnown) {
            report.append("；LSPosed 历史共享目录未确认，请启用模块后重启 Observatory 再检查");
        }
        attempted = true;
        lastReport = report.toString();
        return lastReport;
    }

    private static File preferencePath(Context context, String name) {
        try {
            Method method = Context.class.getMethod("getSharedPreferencesPath", String.class);
            Object path = method.invoke(context, name);
            return path instanceof File ? (File) path : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    static List<Target> fixedTargets(File data, File external) {
        List<Target> targets = new ArrayList<>();
        for (String name : PREFERENCES) {
            targets.add(new Target(data, "shared_prefs/" + name + ".xml", "旧私有偏好 " + name));
            targets.add(new Target(data, "shared_prefs/" + name + ".xml.bak", "旧私有偏好备份 " + name));
        }
        targets.add(new Target(data, "cache/wechat-observatory-config.properties", "旧缓存副本"));
        if (external != null) {
            targets.add(new Target(external, "Android/media/cc.wechat.observatory/config.properties", "旧媒体目录副本"));
            targets.add(new Target(external, "Download/wechat-observatory/config.properties", "旧下载目录副本"));
        }
        targets.add(new Target(new File("/data/local/tmp"), "wechat-observatory/config.properties", "旧 Root 临时副本"));
        return targets;
    }

    static final class Target {
        final File base;
        final String relative;
        final File file;
        final String label;

        Target(File base, String relative, String label) {
            this.base = base;
            this.relative = relative;
            this.file = new File(base, relative);
            this.label = label;
        }

        boolean isSafePath() throws IOException {
            // Permit Android's root aliases, but never follow a changed child directory
            // or file symlink into an unrelated location.
            return file.getCanonicalPath().equals(new File(base.getCanonicalFile(), relative).getAbsolutePath());
        }
    }

    enum Removal { DONE, NEEDS_ACCESS, REFUSED_PATH }

    interface FileAccess {
        void unlinkIfPresent(File file) throws IOException;
    }

    static Removal remove(Target target, FileAccess access) {
        try {
            if (!target.isSafePath()) return Removal.REFUSED_PATH;
            access.unlinkIfPresent(target.file);
            return Removal.DONE;
        } catch (IOException | SecurityException ignored) {
            // File.exists() cannot distinguish a missing file from denied traversal.
            return Removal.NEEDS_ACCESS;
        }
    }

    private static final FileAccess ANDROID_FILES = file -> {
        try {
            Os.unlink(file.getAbsolutePath());
        } catch (ErrnoException error) {
            if (error.errno != OsConstants.ENOENT && error.errno != OsConstants.ENOTDIR) {
                throw new IOException("legacy file deletion failed", error);
            }
        }
    };

    static String rootCommand(List<Target> targets) throws IOException {
        StringBuilder command = new StringBuilder("set -f\n");
        for (Target target : targets) {
            if (!target.isSafePath()) throw new IOException("legacy path changed");
            File base = target.base.getCanonicalFile();
            File parent = new File(base, target.relative).getParentFile();
            // Recheck each child directory in the privileged process before unlinking.
            while (parent != null && !parent.equals(base)) {
                command.append("[ ! -L ").append(shellQuote(parent.getAbsolutePath())).append(" ] || exit 21\n");
                parent = parent.getParentFile();
            }
            String path = shellQuote(new File(base, target.relative).getAbsolutePath());
            command.append("[ ! -L ").append(path).append(" ] || exit 21\n")
                    .append("rm -f -- ").append(path).append(" || exit 22\n")
                    .append("[ ! -e ").append(path).append(" ] && [ ! -L ").append(path).append(" ] || exit 23\n");
        }
        return command.append("exit 0\n").toString();
    }

    private static String removeWithRoot(List<Target> targets) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", rootCommand(targets)).redirectErrorStream(true).start();
            long deadline = android.os.SystemClock.elapsedRealtime() + 10000L;
            while (true) {
                try {
                    return process.exitValue() == 0 ? "" : "权限被拒绝或文件仍未删除";
                } catch (IllegalThreadStateException running) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) return "授权等待超时";
                    Thread.sleep(100L);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "清理已中断";
        } catch (IOException | SecurityException ignored) {
            return "无法运行 Root 清理";
        } finally {
            if (process != null) {
                process.destroy();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
            }
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
