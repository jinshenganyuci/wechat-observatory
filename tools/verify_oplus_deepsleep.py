#!/usr/bin/env python3
"""Read-only DexSQL checks for the reviewed PJZ110 Battery eligibility hooks.

Accepts only the audited Battery APK, not another APK with similar class names.
No device access, settings writes, network calls, or runtime success inference.
Uses the standard-library DexSQL client beside this script.
"""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

from verify_wechat8079 import DexSQL, sql_values
from verify_oplus_realtime import decode, sha256, FINGERPRINT


APK_SHA256 = "752597dd6a739ddd8bbfd9991a31465790e9b846004a96458223948d5ee32ebc"
C = "Lcom/oplus/deepsleep/ControllerCenter;"
W = C[:-1] + "$WorkHandler;"
U = "Lcom/oplus/deepsleep/DeepSleepUtils;"
R = "Lp5/a;"
ELIGIBILITY = C + "->isNotAllowDisableNetwork()Z"
NETWORK = C + "->getNetWorkType()Z"
SENSING = C + "->stepStatusSensingLocked()V"
ENTER = C + "->enterDeepSleepLocked()V"
DISABLE = C + "->disableNetWork(Z)V"
RESTORE = C + "->restoreNetWork(ZZ)V"
RECONFIRM = C + "->reconfirmNetSwitch(Z)V"
DISPATCH = W + "->handleMessage(Landroid/os/Message;)V"
FREEZE = C + "->handleFreezeStrictWhileSettingsNotOpen(Z)V"
METHODS = dict.fromkeys([
    ELIGIBILITY, NETWORK, SENSING, ENTER, DISABLE, RESTORE, RECONFIRM,
    DISPATCH, FREEZE, R + "->r()Z", R + "->F0(Ljava/lang/String;)V",
    R + "->V(Ljava/lang/String;)Z", R + "-><init>(Landroid/content/Context;)V",
    U + "->getDisNetType()I", U + "->getDeepSleepResetAirPlaneStatus()Z",
    C + "->setWifiEnable(ZLjava/lang/String;)V",
    C + "->setMobileDataEnable(ZLjava/lang/String;)V",
    C + "->useOldFunctionDisNetwork()V",
], False)
METHODS.update({
    C + "->B(" + C + ")Ljava/lang/Object;": True,
    C + "->L(" + C + ")I": True,
    C + "->T(" + C + ")Z": True,
    C + "->V0(" + C + "ZLjava/lang/String;)V": True,
    C + "->Y0(" + C + "ZLjava/lang/String;)V": True,
})
FIELDS = [
    C + "->mStatus:I", C + "->mNetWorkSwitchState:I", C + "->mNetWorkType:I",
    C + "->mDisableNetAllowed:Z", C + "->mIsPidDisNet:Z",
    C + "->mIsNetAbnormalActive:Z", C + "->mIsNeedRestoreMobile:Z",
    C + "->mWifiEnalbe:Z", C + "->mLock:Ljava/lang/Object;",
    C + "->mContext:Landroid/content/Context;", C + "->mWorkHandler:" + W,
    C + "->mUtils:" + U, C + "->mConfigUpdateUtil:" + R,
    W + "->this$0:" + C, R + "->P:Z",
]
CALLS = [
    (SENSING, NETWORK), (NETWORK, ELIGIBILITY), (SENSING, ENTER), (ENTER, DISABLE),
    (DISABLE, FREEZE), (RESTORE, FREEZE), (RECONFIRM, FREEZE),
    (DISABLE, R + "->r()Z"), (R + "->V(Ljava/lang/String;)Z", R + "->F0(Ljava/lang/String;)V"),
    (C + "->V0(" + C + "ZLjava/lang/String;)V", C + "->setMobileDataEnable(ZLjava/lang/String;)V"),
    (C + "->Y0(" + C + "ZLjava/lang/String;)V", C + "->setWifiEnable(ZLjava/lang/String;)V"),
]
ACCESS = [
    (R + "->r()Z", R + "->P:Z"),
    (C + "->B(" + C + ")Ljava/lang/Object;", C + "->mLock:Ljava/lang/Object;"),
    (C + "->L(" + C + ")I", C + "->mStatus:I"),
    (C + "->T(" + C + ")Z", C + "->mWifiEnalbe:Z"),
]


def switch_case(body, value):
    """Select this APK's case region, including its internal branch labels."""
    table = re.search(r"\.packed-switch (0x[0-9a-f]+)\s+(.*?)\.end packed-switch", body, re.S)
    if not table:
        return ""
    labels = re.findall(r":label_[0-9a-f]+", table.group(2))
    index = value - int(table.group(1), 16)
    if not 0 <= index < len(labels):
        return ""
    starts = {}
    for label in set(labels):
        match = re.search(r"^\s*" + re.escape(label) + r"\s*\n", body, re.M)
        if match:
            starts[label] = (match.start(), match.end())
    if labels[index] not in starts:
        return ""
    start, content = starts[labels[index]]
    end = min([offset for offset, _ in starts.values() if offset > start] + [table.start()])
    return body[content:end]


def verify(executable, apk):
    digest = sha256(apk)
    report = {
        "schema": 1, "status": "fail", "expected_fingerprint": FINGERPRINT,
        "input": {"file": apk.name, "sha256": digest, "bytes": apk.stat().st_size},
        "scope": "Static Battery APK contract; no device or active power-policy observations",
        "checks": [],
        "limits": [
            "Runtime eligibility also requires the exact build, Battery version, UID and live system-server token/thermal query.",
            "The cycle marker maintains one selected zero-network-change phase; next OEM eligibility evaluation discards it.",
            "This does not establish actual LSPosed installation, RUS values, long-sleep delivery, or immediate policy revocation.",
        ],
    }

    def check(name, ok, **evidence):
        report["checks"].append({"check": name, "ok": bool(ok), **evidence})

    check("audited_apk_sha256", digest == APK_SHA256, expected=APK_SHA256)
    if digest != APK_SHA256:
        report["error"] = "Unknown Battery APK; matching names do not establish compatibility"
        return report
    report["dexsql_version"] = subprocess.check_output([str(executable), "--version"], text=True, timeout=10).strip()
    client = DexSQL(executable)
    try:
        client.dataset = client.call("open_dataset", {"inputs": [str(apk)]})["dataset_id"]

        def query(sql):
            return client.query([sql])[0]

        classes = query("SELECT descriptor,access_flags,superclass_descriptor FROM classes WHERE descriptor IN ("
                        + sql_values([C, W, U, R]) + ")")
        methods = query("SELECT method_descriptor,access_flags FROM class_methods WHERE method_descriptor IN ("
                        + sql_values(METHODS) + ")")
        fields = query("SELECT field_descriptor,access_flags FROM class_fields WHERE field_descriptor IN ("
                       + sql_values(FIELDS) + ")")
        calls = query("SELECT method,callee FROM method_calls WHERE method IN ("
                      + sql_values(sorted({m for m, _ in CALLS})) + ") AND callee IN ("
                      + sql_values(sorted({m for _, m in CALLS})) + ")")
        accesses = query("SELECT method,field,using_type FROM method_field_accesses WHERE method IN ("
                         + sql_values([m for m, _ in ACCESS]) + ") AND field IN (" + sql_values(FIELDS) + ")")
        for cls in [C, W, U, R]:
            rows = [row for row in classes if row["descriptor"] == cls]
            check("concrete_class_definition", len(rows) == 1 and not int(rows[0]["access_flags"]) & (0x200 | 0x400), descriptor=cls)
        check("work_handler_extends_android_handler", any(row["descriptor"] == W and row["superclass_descriptor"] == "Landroid/os/Handler;" for row in classes))
        for descriptor, static in METHODS.items():
            flags = [int(row["access_flags"]) for row in methods if row["method_descriptor"] == descriptor]
            check("concrete_method_definition", len(flags) == 1 and bool(flags[0] & 8) == static
                  and not flags[0] & (0x100 | 0x400), descriptor=descriptor, static=static, access_flags=flags)
        for descriptor in FIELDS:
            flags = [int(row["access_flags"]) for row in fields if row["field_descriptor"] == descriptor]
            check("instance_field_definition", len(flags) == 1 and not flags[0] & 8, descriptor=descriptor, access_flags=flags)
        call_set = {(row["method"], row["callee"]) for row in calls}
        for method, callee in CALLS:
            check("call_edge", (method, callee) in call_set, caller=method, callee=callee)
        access_set = {(row["method"], row["field"], row["using_type"]) for row in accesses}
        for method, field in ACCESS:
            check("getter_field", (method, field, "get") in access_set, method=method, field=field)

        # Fixed-APK checks deliberately follow exact registers and branch blocks;
        # they are not semantic promises about future vendor recompilations.
        network = decode(client, NETWORK)
        denied = network.split("    :label_0028", 1)[0]
        check("denied_eligibility_selects_real_zero_mode", all(part in denied for part in [
            "const/4 v2, 0x1", "const/4 v3, 0x0", "if-eqz v0, :label_0028",
            "iput v3, v10, " + C + "->mNetWorkSwitchState:I",
            "iput v3, v10, " + C + "->mNetWorkType:I",
            "iput-boolean v3, v10, " + C + "->mDisableNetAllowed:Z", "return v2",
        ]))
        disable = decode(client, DISABLE)
        check("zero_disable_uses_freeze_mode_6", "if-nez v13, :label_0092\n    invoke-direct {v12, v3}, " + FREEZE + "\n    const/4 v0, 0x6" in disable)
        restore = decode(client, RESTORE)
        check("zero_restore_does_not_call_radio_setters", "if-nez v1, :label_0117\n    invoke-direct {v12, v5}, " + FREEZE + "\n    :label_0117" in restore)
        for method, key in [(U + "->getDisNetType()I", "deep_sleep_dis_net_type"),
                            (U + "->getDeepSleepResetAirPlaneStatus()Z", "deepsleep_reset_airplane")]:
            body = decode(client, method)
            check("read_only_system_setting_getter", '"' + key + '"' in body
                  and "Landroid/provider/Settings$System;->getInt(" in body and "->put" not in body,
                  method=method, setting=key)
        config = decode(client, R + "->V(Ljava/lang/String;)Z")
        check("airplane_rus_key", '"DeepSleepResetAirPlane"' in config and R + "->F0(Ljava/lang/String;)V" in config)
        dispatch = decode(client, DISPATCH)
        for what, accessor in [(19, "V0"), (20, "Y0")]:
            block = switch_case(dispatch, what)
            check("late_message_is_status4_false_only", "if-ne v9, v5," in block
                  and "invoke-static {v9, v7, v1}, " + C + "->" + accessor + "(" + C + "ZLjava/lang/String;)V" in block
                  and "const/4 v5, 0x4" in dispatch and "const/4 v7, 0x0" in dispatch, message=what)
        wifi = switch_case(dispatch, 11)
        check("wifi_message_uses_actual_enable_field", C + "->T(" + C + ")Z" in wifi
              and "invoke-static {v9, v1, v2}, " + C + "->Y0(" + C + "ZLjava/lang/String;)V" in wifi)
        check("airplane_message_is_separate_17", '"AirEnable"' in switch_case(dispatch, 17))
        check("restore_fallback_24_is_enable_true", "TelephonyManager;->setDataEnabled(Z)V" in switch_case(dispatch, 24)
              and "invoke-virtual {v9, v6}" in switch_case(dispatch, 24) and "const/4 v6, 0x1" in dispatch)
        mixed = switch_case(dispatch, 23)
        cleanup = "->P0(" + C + "ZZ)V"
        redisable = "->K0(" + C + "ZI)V"
        check("mixed_message_23_contains_cleanup_before_redisable", cleanup in mixed
              and redisable in mixed and mixed.index(cleanup) < mixed.index(redisable))
        report["status"] = "pass" if all(item["ok"] for item in report["checks"]) else "fail"
        return report
    finally:
        client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dexsql", type=Path, required=True)
    parser.add_argument("--battery-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.dexsql.resolve(), args.battery_apk.resolve())
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as error:
        report = {"schema": 1, "status": "fail", "error": str(error)}
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded, encoding="utf-8")
    sys.stdout.write(encoded)
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
