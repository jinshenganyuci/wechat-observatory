package cc.wechat.observatory.wechat;

/** Exact APK versions whose account bindings have been checked against bytecode. */
public final class WeChatVersionProfile {
    private static final WeChatVersionProfile[] SUPPORTED = {
            new WeChatVersionProfile("8.0.74", 3120, "gm0.j1", "u", "l"),
            new WeChatVersionProfile("8.0.78", 3180, "gp0.j1", "x", "m"),
            new WeChatVersionProfile("8.0.79", 3200, "pp0.i1", "x", "m")
    };

    public final String versionName;
    public final long versionCode;
    final String accountKernelClass;
    final String accountStorageMethod;
    final String configGetterMethod;

    private WeChatVersionProfile(String versionName, long versionCode, String accountKernelClass,
                                 String accountStorageMethod, String configGetterMethod) {
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.accountKernelClass = accountKernelClass;
        this.accountStorageMethod = accountStorageMethod;
        this.configGetterMethod = configGetterMethod;
    }

    public static WeChatVersionProfile forVersion(String versionName, long versionCode) {
        for (WeChatVersionProfile profile : SUPPORTED) {
            if (profile.versionCode == versionCode && profile.versionName.equals(versionName)) {
                return profile;
            }
        }
        throw new UnsupportedOperationException("Unsupported WeChat version " + versionName + "/"
                + versionCode + "; verified versions: 8.0.74/3120, 8.0.78/3180, 8.0.79/3200");
    }

    public boolean is8079() {
        return versionCode == 3200 && "8.0.79".equals(versionName);
    }
}
