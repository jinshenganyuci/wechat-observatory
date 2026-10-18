# Temporary native file-send experiment — do not merge

This branch is an isolated test build based on main cf1d186. It does not add a
public file-send API. It must be replaced with the original 0.1.22 APK after
the experiment. The production backend and main branch are unchanged.

The laboratory receiver requires android.permission.DUMP, Android user 999,
Android 16, WeChat 8.0.79/3200 and the registered device `phone-test`.
Recipients are hardcoded to `filehelper`. No arbitrary paths, content, URLs
or recipients are accepted. Three fixed cases generate test files locally.
Exclusive, synced journals prevent duplicate native submission per case.

Native dispatch: gu.o0(filehelper, path), n(displayName), rt5.w.a.c(params).
Native observation: gu.m0.Z tracks the copy and gu.m0.h tracks Finish.
Message status 2 is NOT enough: require all prepare/upload/send/update/first
CGI result objects, all codes zero, Finish, exactly one matching outgoing
message with a server ID, and a matching SHA-256 through WeChat VFS.

Only our independent no_backup/observatory-file-probe staging is deleted.
WeChat-owned attachment copies and chat records are preserved. The native
isNeedCleanFile option remains false because it can delete WeChat attachments.
The finish operation refuses unknown files or unconfirmed source cleanup.
