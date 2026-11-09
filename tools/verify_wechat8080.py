#!/usr/bin/env python3
"""Verify WeChat 8.0.80/3220 arm64 definitions and reviewed send/account/@ semantics.

Uses the shared DexSQL verifier; no phone, APK modification or network access.
This evidence is necessary but does not replace actual LSPosed/send validation.
"""
import re
import sys
import xml.etree.ElementTree as ET
import verify_wechat8079 as base
from verify_wechat8080_files import checks as file_checks

EXPECTED_SHA256 = "9d9b6c516e8e07e437c81485fde21fe13ce4f61b37d19e793fff40c71be39c0e"
# Both APKs independently passed the complete contract (download and installed base.apk).
EXPECTED_SHA256S = (EXPECTED_SHA256, "024c53bfc192c463d56b66a676d9133f6d5b1cdcd435601d02d4f6632a333152")
EXPECTED_VERSION = {"package": "com.tencent.mm", "version_name": "8.0.80", "version_code": 3220}

# Only reviewed names change. The shared verifier checks exact definitions and types.
RENAMES = {
    "Lim/c8;": "Llm/d8;", "Lqs/": "Lus/", "Lni5/n0;": "Lfi5/n0;",
    "Lf61/": "Lq61/", "Ll41/d2;": "Lw41/e2;", "Ll41/y1;": "Lw41/z1;",
    "Lpp0/i1;": "Lzp0/j1;", "Lpp0/a0;": "Lzp0/b0;", "Lpp0/f;": "Lzp0/f;",
    "Lcom/tencent/mm/storage/m3;": "Lcom/tencent/mm/storage/r3;",
    "Lcom/tencent/mm/storage/e9;->t2(": "Lcom/tencent/mm/storage/f9;->s2(",
}


def mapped(value):
    for old, new in RENAMES.items():
        value = value.replace(old, new)
    return value.replace("Lq61/s1;->h(Ljava/lang/String;)", "Lq61/s1;->g(Ljava/lang/String;)").replace(
        "Lq61/s1;->i(I)", "Lq61/s1;->h(I)")


FIELDS = [(mapped(name), static) for name, static in base.FIELDS]
METHODS = [(mapped(name), static) for name, static in base.METHODS]
CLASSES = sorted({name.split("->")[0] for name, _ in FIELDS + METHODS} | {
    "Lus/h;", "Lq61/p1;", "Lcom/tencent/mm/modelbase/n1;"})
SETTERS = [(mapped(method), mapped(field)) for method, field in base.SETTERS]
ACCOUNT_GETTERS = [(mapped(method), slot) for method, slot in base.ACCOUNT_GETTERS]
CONFIG_GETTER = mapped(base.CONFIG_GETTER)
PATH_GETTER = mapped(base.PATH_GETTER)
DIRECTORY_GETTER = mapped(base.DIRECTORY_GETTER)
ACCOUNT_GUARD = mapped(base.ACCOUNT_GUARD)


def smali(client, descriptor):
    rows = client.query(["SELECT file_id FROM class_methods WHERE method_descriptor="
                         + base.sql_values([descriptor])])[0]
    if len(rows) != 1:
        raise RuntimeError("Expected one method definition: " + descriptor)
    offset, parts = 0, []
    while True:
        result = client.call("decode", {"dataset_id": client.dataset, "file_id": int(rows[0]["file_id"]),
                                       "method": descriptor, "offset": offset,
                                       "max_lines": 1000, "max_bytes": 131072})
        method = result["methods"][0]
        parts.append(method["smali"])
        next_offset = method.get("next_offset")
        if next_offset is None:
            return "".join(parts)
        if next_offset <= offset:
            raise RuntimeError("Non-advancing method evidence: " + descriptor)
        offset = next_offset


def additional_checks(client):
    parse = smali(client, "Llm/d8;->parseBuff()V")
    reader = smali(client, "Lcom/tencent/mm/sdk/platformtools/e2;->g()Ljava/lang/String;")
    source = smali(client, "Lcom/tencent/mm/storage/f9;->s2(Ljava/lang/String;)Z")
    listener = smali(client, "Lvt/u5;->callback(Lcom/tencent/mm/sdk/event/IEvent;)Z")
    build = smali(client, "Lq61/s1;->a()Lq61/o1;")
    execute = smali(client, "Lq61/o1;->a()Z")
    reads = re.findall(r"invoke-virtual[^\n]*Lcom/tencent/mm/sdk/platformtools/e2;->([a-z])\(", parse)
    return [
        {"check": "LVBuffer msgSource remains third field (string, int, string)",
         "ok": reads[:7] == ["i", "c", "g", "c", "e", "c", "g"]
               and "Llm/d8;->G:Ljava/lang/String;" in parse},
        {"check": "LVBuffer strings use short byte length and UTF-8",
         "ok": "Ljava/nio/ByteBuffer;->getShort()S" in reader and '"UTF-8"' in reader},
        {"check": "native mention test parses msgsource.atuserlist from field G",
         "ok": "Llm/d8;->G:Ljava/lang/String;" in source and '".msgsource.atuserlist"' in source},
        {"check": "native SendMsgEvent uses the selected recipient, text and request methods",
         "ok": all(value in listener for value in ["Lq61/t1;->a(Ljava/lang/String;)Lq61/s1;",
                "Lq61/s1;->g(Ljava/lang/String;)Lq61/s1;", "Lq61/s1;->e(Ljava/lang/String;)Lq61/s1;",
                "Lq61/s1;->i:I", "Lq61/s1;->a()Lq61/o1;", "Lq61/o1;->a()Z"])},
        {"check": "prepared request preserves native scene and local message ID",
         "ok": all(value in build for value in ["Lq61/n1;->a:Lcom/tencent/mm/modelbase/n1;",
                "Lq61/o1;->a:Lcom/tencent/mm/modelbase/n1;", "Lq61/n1;->b:J", "Lq61/o1;->b:J"])},
        {"check": "execution enqueues the prepared scene without rebuilding",
         "ok": "Lcom/tencent/mm/modelbase/s1;->h(Lcom/tencent/mm/modelbase/n1;I)Z" in execute
                and "Lq61/s1;" not in execute},
    ] + settings_checks(client) + file_checks(client, smali)


def settings_checks(client):
    setting = "Lcom/tencent/mm/plugin/setting/ui/setting_new/"
    main = setting + "MainSettingsUI;"
    base_class = setting + "base/BaseSettingPrefUI;"
    classes, methods, constants = client.query([
        "SELECT superclass_descriptor FROM classes WHERE descriptor=" + base.sql_values([main]),
        "SELECT method_descriptor,access_flags FROM class_methods WHERE method_descriptor IN ("
        + base.sql_values([base_class + "->onCreate(Landroid/os/Bundle;)V", base_class + "->getLayoutId()I"]) + ")",
        "SELECT long_value FROM method_using_numbers WHERE method=" + base.sql_values([base_class + "->getLayoutId()I"]),
    ])
    checks = [
        {"check": "main settings directly extends verified base lifecycle",
         "ok": len(classes) == 1 and classes[0]["superclass_descriptor"] == base_class
               and len(methods) == 2 and all(not int(row["access_flags"]) & (0x8 | 0x400) for row in methods)},
        {"check": "settings getLayoutId returns reviewed layout ID",
         "ok": [int(row["long_value"]) for row in constants] == [0x7f0e1380]},
    ]
    for name, resource_id, value in [("layout/ept", 0x7f0e1380, "r/p/ept.xml"),
            ("id/jlw", 0x7f0a48fd, "false"), ("id/jlv", 0x7f0a48fc, "false"),
            ("id/lqa", 0x7f0a578b, "false")]:
        result = client.call("query_resources", {"dataset_id": client.dataset, "name": name,
                                               "configuration": "", "limit": 10})
        values = result["values"]
        checks.append({"check": "settings resource identity " + name,
                       "ok": not result["truncated"] and len(values) == 1
                       and values[0]["resource_id"] == resource_id and values[0]["value"] == value
                       and values[0]["package"] == "com.tencent.mm"})
    page = client.call("read_resource", {"dataset_id": client.dataset, "name": "r/p/ept.xml",
                                        "max_bytes": 32768})["page"]
    if page.get("next_offset") is not None:
        raise RuntimeError("Incomplete settings XML evidence")
    root = ET.fromstring(page["text"])
    attr = "{http://schemas.android.com/apk/res/android}"
    shape_ok = (root.tag == "RelativeLayout" and root.get(attr + "id") == "@7f0a48fd"
                and len(root) == 1 and root[0].tag == "LinearLayout"
                and root[0].get(attr + "id") == "@7f0a48fc"
                and root[0].get(attr + "orientation") == "vertical"
                and len(root[0]) > 1 and root[0][1].tag == "RelativeLayout"
                and root[0][1].get(attr + "layout_weight") == "1" and len(root[0][1]) > 0
                and root[0][1][0].tag == "com.tencent.mm.view.recyclerview.WxRecyclerView"
                and root[0][1][0].get(attr + "id") == "@7f0a578b")
    checks.append({"check": "settings XML keeps vertical insertion parent and weighted list", "ok": shape_ok})
    return checks


def verify(executable, apk):
    return base.verify(executable, apk, sys.modules[__name__])


if __name__ == "__main__":
    sys.exit(base.main(verify))
