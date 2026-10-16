#!/usr/bin/env python3
"""Verify the exact 8.0.79/3200 arm64 adapter contract with local DexSQL.

No network, Android device, APK modification, or Python dependencies are used.
Run: python3 tools/verify_wechat8079.py --dexsql /path/to/dexsql --apk /path/to.apk
Use --output path.json to save the same compact evidence printed to stdout.
This checks real definitions and selected bytecode relations, not DEX strings.
It does not establish that LSPosed injected the module or a message was delivered.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile


EXPECTED_SHA256 = "f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d"
EXPECTED_VERSION = {"package": "com.tencent.mm", "version_name": "8.0.79", "version_code": 3200}

# Exact field descriptors include both the declaring class and field type.
FIELDS = [
    ("Lqs/g;->a:[Lqs/h;", True),
    ("Lni5/n0;->f:[Z", True),
    ("Lf61/t1;->a:Lf61/p1;", True),
    ("Lf61/s1;->b:Ljava/lang/String;", False),
    ("Lf61/s1;->d:Ljava/lang/String;", False),
    ("Lf61/s1;->e:I", False),
    ("Lf61/s1;->f:I", False),
    ("Lf61/s1;->i:I", False),
    ("Lf61/o1;->a:Lcom/tencent/mm/modelbase/n1;", False),
    ("Lf61/o1;->b:J", False),
]
# Full descriptors assert the argument list AND return type.
METHODS = [
    ("Lcom/tencent/mm/modelbase/a3;->c()Z", True),
    ("Lf61/t1;->a(Ljava/lang/String;)Lf61/s1;", True),
    ("Lf61/s1;->h(Ljava/lang/String;)Lf61/s1;", False),
    ("Lf61/s1;->e(Ljava/lang/String;)Lf61/s1;", False),
    ("Lf61/s1;->i(I)Lf61/s1;", False),
    ("Lf61/s1;->a()Lf61/o1;", False),
    ("Lf61/o1;->a()Z", False),
    ("Ll41/d2;->C(Ljava/lang/String;)I", True),
    ("Lpp0/i1;->x()Lpp0/a0;", True),
    ("Lpp0/a0;->g()Ljava/lang/String;", False),
    ("Lpp0/a0;->h()Ljava/lang/String;", False),
    ("Lpp0/a0;->c()Lcom/tencent/mm/storage/m3;", False),
    ("Lcom/tencent/mm/storage/m3;->m(ILjava/lang/Object;)Ljava/lang/Object;", False),
    ("Ll41/y1;->u()Ljava/lang/String;", True),
    ("Ll41/y1;->m()Ljava/lang/String;", True),
]
CLASSES = sorted({item.split("->")[0] for item, _ in FIELDS + METHODS} | {
    "Lqs/h;", "Lf61/p1;", "Lcom/tencent/mm/modelbase/n1;",
})
SETTERS = [
    ("Lf61/s1;->h(Ljava/lang/String;)Lf61/s1;", "Lf61/s1;->b:Ljava/lang/String;"),
    ("Lf61/s1;->e(Ljava/lang/String;)Lf61/s1;", "Lf61/s1;->d:Ljava/lang/String;"),
    ("Lf61/s1;->i(I)Lf61/s1;", "Lf61/s1;->e:I"),
]
ACCOUNT_GETTERS = [
    ("Ll41/y1;->u()Ljava/lang/String;", 2),
    ("Ll41/y1;->m()Ljava/lang/String;", 4),
]
CONFIG_GETTER = "Lcom/tencent/mm/storage/m3;->m(ILjava/lang/Object;)Ljava/lang/Object;"
PATH_GETTER = "Lpp0/a0;->g()Ljava/lang/String;"
DIRECTORY_GETTER = "Lpp0/a0;->h()Ljava/lang/String;"


def sql_values(values):
    return ",".join("'" + value.replace("'", "''") + "'" for value in values)


class DexSQL:
    """Small JSON-lines MCP client; stdout stays machine-readable."""

    def __init__(self, executable):
        self.stderr = tempfile.TemporaryFile(mode="w+t")
        self.process = subprocess.Popen(
            [str(executable), "mcp"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=self.stderr, text=True, encoding="utf-8")
        self.sequence = 0
        self.dataset = None
        self.request("initialize", {
            "protocolVersion": "2024-11-05", "capabilities": {},
            "clientInfo": {"name": "wechat8079-binding-verifier", "version": "1"},
        })
        self.process.stdin.write(json.dumps({
            "jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n")
        self.process.stdin.flush()

    def request(self, method, params):
        self.sequence += 1
        self.process.stdin.write(json.dumps({
            "jsonrpc": "2.0", "id": self.sequence, "method": method, "params": params,
        }) + "\n")
        self.process.stdin.flush()
        while True:
            line = self.process.stdout.readline()
            if not line:
                self.stderr.seek(0)
                raise RuntimeError("DexSQL exited: " + self.stderr.read()[-2000:])
            reply = json.loads(line)
            if reply.get("id") == self.sequence:
                if "error" in reply:
                    raise RuntimeError(str(reply["error"]))
                return reply["result"]

    def call(self, name, arguments):
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        if result.get("isError"):
            raise RuntimeError(str(result))
        if "structuredContent" in result:
            return result["structuredContent"]
        for block in result.get("content", []):
            if block.get("type") == "text":
                return json.loads(block["text"])
        raise RuntimeError("DexSQL returned no structured result")

    def close(self):
        if self.dataset is not None and self.process.poll() is None:
            try:
                self.call("close_dataset", {"dataset_id": self.dataset})
            except (RuntimeError, BrokenPipeError):
                pass
        if self.process.poll() is None:
            self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
        self.stderr.close()

    def query(self, statements):
        result = self.call("query", {
            "dataset_id": self.dataset, "sql": statements,
            "limit": 1000, "max_bytes": 131072,
        })
        if len(result["statements"]) != len(statements):
            raise RuntimeError("DexSQL omitted a query result")
        decoded = []
        for statement in result["statements"]:
            if statement["truncated"]:
                raise RuntimeError("DexSQL truncated a binding result")
            names = [column["name"] for column in statement["columns"]]
            decoded.append([
                dict(zip(names, [cell["value"] for cell in row]))
                for row in statement["rows"]
            ])
        return decoded


def check_definitions(expected, rows, descriptor_key):
    checked = []
    for descriptor, is_static in expected:
        found = [row for row in rows if row[descriptor_key] == descriptor]
        flags = [int(row["access_flags"]) for row in found]
        checked.append({
            "descriptor": descriptor, "static": is_static,
            "definition_count": len(found), "access_flags": flags,
            "ok": len(found) == 1 and bool(flags[0] & 0x8) == is_static,
        })
    return checked


def verify(executable, apk):
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    report = {
        "schema": 1, "verifier": "tools/verify_wechat8079.py",
        "apk": {"filename": apk.name, "sha256": digest.hexdigest(), "bytes": apk.stat().st_size},
        "expected_apk_sha256": EXPECTED_SHA256,
        "runtime_validation": "not performed; requires a logged-in phone with LSPosed",
        "status": "fail",
    }
    if digest.hexdigest() != EXPECTED_SHA256:
        report["error"] = "APK SHA-256 differs from the bytecode-reviewed 8.0.79/3200 arm64 build"
        return report

    report["dexsql_version"] = subprocess.check_output(
        [str(executable), "--version"], text=True, timeout=10).strip()
    client = DexSQL(executable)
    try:
        client.dataset = client.call("open_dataset", {"inputs": [str(apk)]})["dataset_id"]
        manifest = client.call("get_manifest", {
            "dataset_id": client.dataset, "mode": "summary", "limit": 1,
        })["manifest"]
        version = {"package": manifest.get("package"),
                   "version_name": manifest.get("android:versionName"),
                   "version_code": int(manifest.get("android:versionCode", 0))}
        report["apk"].update(version)
        if version != EXPECTED_VERSION:
            report["error"] = "Manifest package/version does not match com.tencent.mm 8.0.79/3200"
            return report

        # Definition tables exclude cross-DEX method/field references.
        classes, fields, methods, setters, calls, numbers, strings = client.query([
            "SELECT descriptor,access_flags FROM classes WHERE descriptor IN (" + sql_values(CLASSES) + ") ORDER BY descriptor",
            "SELECT field_descriptor,access_flags FROM class_fields WHERE field_descriptor IN (" + sql_values(x[0] for x in FIELDS) + ") ORDER BY field_descriptor",
            "SELECT method_descriptor,access_flags FROM class_methods WHERE method_descriptor IN (" + sql_values(x[0] for x in METHODS) + ") ORDER BY method_descriptor",
            "SELECT method,field,using_type FROM method_field_accesses WHERE method IN (" + sql_values(x[0] for x in SETTERS) + ") ORDER BY method,field",
            "SELECT method,callee FROM method_calls WHERE method IN (" + sql_values([x[0] for x in ACCOUNT_GETTERS] + [PATH_GETTER, DIRECTORY_GETTER]) + ") ORDER BY method,callee",
            "SELECT method,long_value FROM method_using_numbers WHERE method IN (" + sql_values(x[0] for x in ACCOUNT_GETTERS) + ") ORDER BY method,long_value",
            "SELECT method,value FROM method_strings WHERE method=" + sql_values([PATH_GETTER]) + " ORDER BY value",
        ])
        class_checks = [{"descriptor": name, "definition_count": sum(row["descriptor"] == name for row in classes),
                         "ok": sum(row["descriptor"] == name for row in classes) == 1} for name in CLASSES]
        field_checks = check_definitions(FIELDS, fields, "field_descriptor")
        method_checks = check_definitions(METHODS, methods, "method_descriptor")
        semantic_checks = [{
            "check": "setter writes expected field", "method": method, "field": field,
            "ok": any(row["method"] == method and row["field"] == field and row["using_type"] == "put" for row in setters),
        } for method, field in SETTERS]
        for method, slot in ACCOUNT_GETTERS:
            semantic_checks.append({
                "check": "native account getter references ConfigStorage and expected slot constant",
                "method": method, "slot": slot,
                "ok": any(row["method"] == method and row["callee"] == CONFIG_GETTER for row in calls)
                      and any(row["method"] == method and int(row["long_value"]) == slot for row in numbers),
            })
        semantic_checks.extend([
            {"check": "main database path uses guarded account directory and EnMicroMsg.db",
             "ok": any(row["method"] == PATH_GETTER and row["callee"] == DIRECTORY_GETTER for row in calls)
                   and any(row["value"] == "EnMicroMsg.db" for row in strings)},
            {"check": "account directory calls initialization guard",
             "ok": any(row["method"] == DIRECTORY_GETTER and row["callee"] == "Lpp0/f;->c()V" for row in calls)},
        ])
        report["checks"] = {"classes": class_checks, "fields": field_checks,
                            "methods": method_checks, "bytecode_relations": semantic_checks}
        report["status"] = "pass" if all(item["ok"] for group in report["checks"].values() for item in group) else "fail"
        return report
    finally:
        client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dexsql", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.dexsql.resolve(), args.apk.resolve())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        result = {"schema": 1, "status": "error", "error": str(error)}
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    sys.stdout.write(output)
    return 0 if result["status"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
