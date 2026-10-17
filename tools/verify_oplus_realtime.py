#!/usr/bin/env python3
"""Read-only DexSQL verification of the reviewed PJZ110 Hans hook contract.

Only the two locally reviewed Android 16 vendor JARs are accepted. This does
not authorize a different ROM with similar method names, inspect a phone,
change power policy, or establish successful LSPosed injection/idle delivery.
Requires Python's standard library and the adjacent verify_wechat8079.py client.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

from verify_wechat8079 import DexSQL, sql_values


PROFILE = "PJZ110-Android16-V.3205195-ced00f-d0523d"
FINGERPRINT = ("OnePlus/PJZ110/OP5D0DL1:16/BP2A.250605.015/"
               "V.3205195-ced00f-d0523d:user/release-keys")
HASHES = {
    "services": "bca5f72c4daf7a5aaacd78e5b8f82431cc371169dee6cc7ab42f87170bce2180",
    "framework": "3b47a02bec547b3218f8a9ff94e11bb443e985b9b980314a9e0bedd82df8af78",
}
MANAGER = "Lcom/android/server/am/OplusHansManager;"
IMPORTANCE = "Lcom/android/server/hans/OplusHansImportance;"
PACKAGE = "Lcom/android/server/hans/OplusHansPackage;"
SCENE = "Lcom/android/server/hans/scene/HansSceneManager;"
STATE = "Lcom/android/server/hans/states/HansAppStateMachine;"
RUNNING = STATE[:-1] + "$RunningState;"
MIDDLE = STATE[:-1] + "$MiddleState;"
DEFAULT = STATE[:-1] + "$DefaultState;"
NET = "Lcom/android/server/nwpower/OAppNetControlService;"
ATOMIC = "Ljava/util/concurrent/atomic/AtomicInteger;"
IMP2 = IMPORTANCE + "->isImportantPkg(" + PACKAGE + "I)Z"
IMP3 = IMPORTANCE + "->isImportantPkg(" + PACKAGE + "IZ)Z"
IMP4 = IMPORTANCE + "->isImportantPkg(" + PACKAGE + "IZLjava/lang/String;)Z"
DESTROY = MANAGER + "->nwPowerDestroySocketForProc(ILjava/lang/String;I)V"
FIREWALL = MANAGER + "->nwPowerSetFirewall(" + PACKAGE + "ZII)V"
STRICT = MANAGER + "->getStrictModeLevelByScene(I)I"
THERMAL_MNG = SCENE + "->getThermalMngLevel()I"
THERMAL_PRIORITY = SCENE + "->getThermalPriorityLevel()I"
THERMAL_MODE = SCENE + "->isInThermalMode()Z"
THERMAL_TRAFFIC = IMPORTANCE + "->isThermalIgnoreTraffic(Ljava/lang/String;I)Z"
SM1 = STATE + "->isImportantForSM(Z)Z"
SM2 = STATE + "->isImportantForSM(ZLjava/lang/String;)Z"
COMBO = SCENE + "->isImportantForSceneCombo(" + PACKAGE + "ZLjava/lang/String;)Z"
R_MIDDLE = RUNNING + "->isImportantForSMToMiddle()Z"
D_FROZEN = DEFAULT + "->transToFrozen()V"
M_FROZEN = MIDDLE + "->transToFrozen()V"
R_FROZEN = RUNNING + "->transToFrozen(Ljava/lang/String;)V"
SET_STRICT = MANAGER + "->setStrictModeByThermal(I)V"
SET_MNG = SCENE + "->setThermalMngLevel(I)V"
INIT_SCENE = SCENE + "-><init>()V"
RULE_FIELD = PACKAGE + "->ruleAllow:Z"

# These are the methods reflected by HansPolicyContract, including return types.
RUNTIME_METHODS = {
    IMP4: False, DESTROY: False, FIREWALL: False,
    MANAGER + "->getInstance()" + MANAGER: True,
    SCENE + "->getInstance()" + SCENE: True,
    STRICT: False, THERMAL_MNG: False, THERMAL_PRIORITY: False, THERMAL_MODE: False,
    PACKAGE + "->getUid()I": False,
    PACKAGE + "->getPkgName()Ljava/lang/String;": False,
    PACKAGE + "->getLock()Ljava/lang/Object;": False,
    PACKAGE + "->setImportantReason(Ljava/lang/String;)V": False,
}

# Definition checks for the reviewed upstream/downstream call paths. All are
# concrete instance methods; external Android framework references are excluded.
EVIDENCE_METHODS = [
    IMP2, IMP3, SM1, SM2, COMBO, R_MIDDLE, D_FROZEN, M_FROZEN, R_FROZEN,
    RUNNING + "->transToMiddle(Z)V", STATE + "->isInImportanceList(I)Z",
    PACKAGE + "->setRuleAllow(Z)V", SET_STRICT, SET_MNG, INIT_SCENE,
    MANAGER + "->destroySocketForProc(" + PACKAGE + ")V",
    MANAGER + "->applyFirewallRuleDenyByNetList(" + PACKAGE
    + "Lcom/android/server/hans/OplusHansRestriction;)Z",
    NET + "->hansDestroySocketForProc(II)V",
    NET + "->destroySocketForUidPidByBgProcNetControl(II)V",
    IMPORTANCE + "->isThermalSkipImportance(" + PACKAGE + "I)Z",
    THERMAL_TRAFFIC,
    IMPORTANCE + "->isHansImportantCaseLocked(" + PACKAGE + "IZLjava/lang/String;)Z",
]
METHODS = dict.fromkeys(EVIDENCE_METHODS, False)
METHODS.update(RUNTIME_METHODS)
FIELDS = {
    PACKAGE + "->uid:I": False,
    PACKAGE + "->pkgName:Ljava/lang/String;": False,
    PACKAGE + "->importantReason:Ljava/lang/String;": False,
    RULE_FIELD: False,
    MANAGER + "->mStrictModeByThermal:" + ATOMIC: False,
    SCENE + "->mThermalMngLevel:" + ATOMIC: False,
    SCENE + "->mThermalPriorityLevel:" + ATOMIC: False,
    SCENE + "->mThermalMode:Z": False,
}
CLASSES = sorted({item.split("->")[0] for item in METHODS} | {NET})
CALLS = [
    (IMP2, IMP3), (IMP3, IMP4), (SM1, SM2), (SM2, COMBO), (COMBO, IMP4),
    (STATE + "->isInImportanceList(I)Z", IMP2),
    (RUNNING + "->transToMiddle(Z)V", R_MIDDLE), (R_MIDDLE, SM1),
    (D_FROZEN, SM1), (M_FROZEN, SM1), (R_FROZEN, SM2),
    (IMP4, IMPORTANCE + "->isHansImportantCase(" + PACKAGE + "IZLjava/lang/String;)Z"),
    (FIREWALL, PACKAGE + "->setRuleAllow(Z)V"),
    (FIREWALL, "Landroid/nwpower/IOAppNetControlManager;->setFirewallWithArgs(IZII)V"),
    (DESTROY, "Landroid/nwpower/IOAppNetControlManager;->destroySocketForProc(II)V"),
    (MANAGER + "->destroySocketForProc(" + PACKAGE + ")V", DESTROY),
    (MANAGER + "->applyFirewallRuleDenyByNetList(" + PACKAGE
     + "Lcom/android/server/hans/OplusHansRestriction;)Z",
     MANAGER + "->destroySocketForProc(" + PACKAGE + ")V"),
    (NET + "->hansDestroySocketForProc(II)V",
     NET + "->destroySocketForUidPidByBgProcNetControl(II)V"),
    (NET + "->destroySocketForUidPidByBgProcNetControl(II)V",
     "Lcom/android/server/net/linkpower/netlink/NetlinkLinkPower;->requestCloseSkForProc(II)V"),
    (SET_MNG, SET_STRICT),
    (SET_STRICT, MANAGER + "->updateStrictModeScene(IZ)V"),
    (THERMAL_MNG, ATOMIC + "->get()I"),
    (THERMAL_PRIORITY, ATOMIC + "->get()I"),
    (IMPORTANCE + "->isThermalSkipImportance(" + PACKAGE + "I)Z", THERMAL_PRIORITY),
    (THERMAL_TRAFFIC, THERMAL_MODE),
    (IMPORTANCE + "->isHansImportantCaseLocked(" + PACKAGE + "IZLjava/lang/String;)Z", THERMAL_TRAFFIC),
]
ACCESS = [
    (PACKAGE + "->getUid()I", PACKAGE + "->uid:I", "get"),
    (PACKAGE + "->getPkgName()Ljava/lang/String;", PACKAGE + "->pkgName:Ljava/lang/String;", "get"),
    (PACKAGE + "->setImportantReason(Ljava/lang/String;)V", PACKAGE + "->importantReason:Ljava/lang/String;", "put"),
    (PACKAGE + "->setRuleAllow(Z)V", RULE_FIELD, "put"),
    (STRICT, MANAGER + "->mStrictModeByThermal:" + ATOMIC, "get"),
    (THERMAL_MNG, SCENE + "->mThermalMngLevel:" + ATOMIC, "get"),
    (THERMAL_PRIORITY, SCENE + "->mThermalPriorityLevel:" + ATOMIC, "get"),
    (THERMAL_MODE, SCENE + "->mThermalMode:Z", "get"),
]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def decode(client, method):
    parts, offset = [], 0
    for _ in range(32):
        result = client.call("decode", {
            "dataset_id": client.dataset, "method": method, "mode": "smali",
            "offset": offset, "max_lines": 1000, "max_bytes": 131072,
        })
        methods = result.get("methods", [])
        if len(methods) != 1 or methods[0].get("descriptor") != method:
            raise RuntimeError("Ambiguous decoded method: " + method)
        parts.append(methods[0]["smali"])
        next_offset = methods[0].get("next_offset")
        if next_offset is None:
            return "\n".join(parts)
        if next_offset <= offset:
            raise RuntimeError("Non-progressing decode pagination")
        offset = next_offset
    raise RuntimeError("Method exceeded decode limit: " + method)


def thermal_switch_targets_field(smali):
    """Follow case 4 in this reviewed small packed-switch, not a name match."""
    payload = re.search(r"\.packed-switch (0x[0-9a-f]+)\s+(.*?)\.end packed-switch", smali, re.S)
    if not payload:
        return False
    targets = re.findall(r":label_[0-9a-f]+", payload.group(2))
    index = 4 - int(payload.group(1), 16)
    if not 0 <= index < len(targets):
        return False
    block = re.search(r"^\s*" + re.escape(targets[index])
                      + r"\s*\n(.*?)(?=^\s*:|\Z)", smali, re.M | re.S)
    return bool(block and MANAGER + "->mStrictModeByThermal:" + ATOMIC in block.group(1)
                and ATOMIC + "->intValue()I" in block.group(1)
                and re.search(r"move-result (v\d+)\s+return \1", block.group(1)))


def verify(executable, services, framework):
    report = {
        "schema": 1, "profile": PROFILE, "expected_runtime_fingerprint": FINGERPRINT,
        "status": "fail", "scope": "Static vendor-JAR contract only; no device access",
        "limits": [
            "Does not verify LSPosed scope, receiver lifecycle, active policy, or sleep delivery.",
            "RUS/runtime policy and thermal readings are not present in these input files.",
            "AOSP CachedAppOptimizer and other OEM freezer paths are not intercepted by these three hooks.",
        ],
        "inputs": {}, "checks": [],
    }

    def check(name, ok, **evidence):
        report["checks"].append({"check": name, "ok": bool(ok), **evidence})

    for key, path in (("services", services), ("framework", framework)):
        digest = sha256(path)
        report["inputs"][key] = {"file": path.name, "sha256": digest, "bytes": path.stat().st_size}
        check("reviewed_jar_sha256", digest == HASHES[key], input=key, expected=HASHES[key])
    if not all(item["ok"] for item in report["checks"]):
        report["error"] = "Unknown vendor JAR; no compatibility inferred from matching names"
        return report

    report["dexsql_version"] = subprocess.check_output([str(executable), "--version"], text=True, timeout=10).strip()
    client = DexSQL(executable)
    try:
        client.dataset = client.call("open_dataset", {"inputs": [str(services), str(framework)]})["dataset_id"]
        # Query separately to bound each MCP response. Definition tables exclude
        # references to methods that do not actually exist in these vendor JARs.
        def query(sql):
            return client.query([sql])[0]
        classes = query("SELECT descriptor,access_flags FROM classes WHERE descriptor IN (" + sql_values(CLASSES) + ")")
        methods = query("SELECT method_descriptor,access_flags FROM class_methods WHERE method_descriptor IN (" + sql_values(METHODS) + ")")
        fields = query("SELECT field_descriptor,access_flags FROM class_fields WHERE field_descriptor IN (" + sql_values(FIELDS) + ")")
        calls = query("SELECT method,callee FROM method_calls WHERE method IN (" + sql_values(sorted({m for m, _ in CALLS})) + ") AND callee IN (" + sql_values(sorted({c for _, c in CALLS})) + ")")
        accesses = query("SELECT method,field,using_type FROM method_field_accesses WHERE method IN (" + sql_values([m for m, _, _ in ACCESS]) + ") AND field IN (" + sql_values(FIELDS) + ")")
        for cls in CLASSES:
            count = sum(row["descriptor"] == cls for row in classes)
            check("class_definition", count == 1, descriptor=cls, count=count)
        for descriptor, static in METHODS.items():
            rows = [row for row in methods if row["method_descriptor"] == descriptor]
            flags = [int(row["access_flags"]) for row in rows]
            public = descriptor in RUNTIME_METHODS
            ok = (len(flags) == 1 and bool(flags[0] & 0x8) == static
                  and not flags[0] & (0x100 | 0x400) and (not public or flags[0] & 0x1))
            check("concrete_method_definition", ok, descriptor=descriptor, static=static,
                  public_required=public, access_flags=flags, runtime_binding=public)
        for descriptor, static in FIELDS.items():
            flags = [int(row["access_flags"]) for row in fields if row["field_descriptor"] == descriptor]
            check("field_definition", len(flags) == 1 and bool(flags[0] & 0x8) == static,
                  descriptor=descriptor, access_flags=flags)
        call_set = {(row["method"], row["callee"]) for row in calls}
        for caller, callee in CALLS:
            check("call_edge", (caller, callee) in call_set, caller=caller, callee=callee)
        access_set = {(row["method"], row["field"], row["using_type"]) for row in accesses}
        for method, field, mode in ACCESS:
            check("field_access", (method, field, mode) in access_set, method=method, field=field, mode=mode)

        check("thermal_scene_4_returns_thermal_strict_atomic", thermal_switch_targets_field(decode(client, STRICT)))
        firewall = decode(client, FIREWALL)
        check("firewall_same_allow_argument_updates_record_and_network",
              "invoke-virtual {v6, v7}, " + PACKAGE + "->setRuleAllow(Z)V" in firewall
              and "invoke-interface {v2, v3, v7, v8, v9}, Landroid/nwpower/IOAppNetControlManager;->setFirewallWithArgs(IZII)V" in firewall)
        destroy = decode(client, DESTROY)
        check("socket_close_forwards_full_uid_and_pid",
              "invoke-interface {v2, v6, v8}, Landroid/nwpower/IOAppNetControlManager;->destroySocketForProc(II)V" in destroy)
        init = decode(client, INIT_SCENE)
        for field in ("mThermalMngLevel", "mThermalPriorityLevel"):
            check("thermal_atomic_initial_value_zero", "const/4 v1, 0x0" in init
                  and "invoke-direct {v4, v1}, " + ATOMIC + "-><init>(I)V\n    iput-object v4, v7, " + SCENE + "->" + field + ":" + ATOMIC in init,
                  field=field)
        thermal_skip = decode(client, IMPORTANCE + "->isThermalSkipImportance(" + PACKAGE + "I)Z")
        check("priority_zero_skips_thermal_override_only",
              re.search(r"move-result v0\s+if-nez v0, :label_[0-9a-f]+\s+const/4 v1, 0x0\s+return v1", thermal_skip) is not None)
        for method in (D_FROZEN, M_FROZEN, R_FROZEN):
            body = decode(client, method)
            check("freeze_transition_branches_on_importance_result",
                  re.search(r"->isImportantForSM\([^\n]+\)Z\s+move-result v0\s+(?:const/16 v4, 0x8\s+)?if-eqz v0, :label_[0-9a-f]+", body) is not None,
                  method=method)
        report["hook_semantics"] = {
            "importance": "A true result protects the normal Hans state-machine transitions; retain original true/exception, label a newly granted exemption.",
            "socket_close": "Before hook may skip this void Hans UID/PID close request for the registered UID only.",
            "firewall": "Argument 1 is ruleAllow. Change false to true and execute original so recorded and actual rules remain consistent.",
            "thermal": "Only strict(scene=4)==0 AND thermal management==0 AND priority==0 AND thermal mode==false is eligible; unknown values/read failures retain original policy.",
        }
        report["status"] = "pass" if all(item["ok"] for item in report["checks"]) else "fail"
        return report
    finally:
        client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dexsql", type=Path, required=True)
    parser.add_argument("--services-jar", type=Path, required=True)
    parser.add_argument("--framework-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.dexsql.resolve(), args.services_jar.resolve(), args.framework_jar.resolve())
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as exc:
        report = {"schema": 1, "profile": PROFILE, "status": "fail", "error": str(exc)}
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded, encoding="utf-8")
    sys.stdout.write(encoded)
    return 0 if report["status"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
