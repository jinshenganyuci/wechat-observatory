"""Native file contract used by verify_wechat8080; never sends a message."""
import re
from verify_wechat8079 import check_definitions, sql_values

FIELDS = [
    ("Llu/o0;->q:Ljava/lang/String;", False),
    ("Llu/o0;->r:Ljava/lang/String;", False),
    ("Llu/o0;->s:Ljava/lang/String;", False),
    ("Llu/o0;->x:Z", False),
    ("Llu/o0;->J:Llu/q0;", False),
    ("Llu/q0;->a:I", False),
    ("Llu/m0;->o:Llu/o0;", False),
    ("Llt5/w;->a:Llt5/v;", True),
    ("Lhr3/i0;->g:Lcom/tencent/mm/plugin/msg/MsgIdTalker;", False),
    ("Lhr3/i0;->a:Lhr3/k0;", False),
    ("Lcom/tencent/mm/plugin/msg/MsgIdTalker;->d:J", False),
    ("Lcom/tencent/mm/plugin/msg/MsgIdTalker;->e:Ljava/lang/String;", False),
    ("Lju/a;->m:Lhr3/j0;", False),
    ("Lju/a;->n:Lhr3/m0;", False),
    ("Lju/a;->o:Lhr3/h0;", False),
    ("Lju/a;->p:Lhr3/o0;", False),
    ("Lhr3/j0;->a:I", False),
    ("Lhr3/m0;->a:I", False),
    ("Lhr3/h0;->a:I", False),
    ("Lhr3/o0;->a:I", False),
    ("Lyx0/k0;->A1:Lyx0/j0;", True),
    ("Lyx0/t;->z1:Lyx0/s;", True),
    ("Llm/s;->field_msgInfoId:J", False),
    ("Llm/s;->field_msgInfoTalker:Ljava/lang/String;", False),
    ("Llm/s;->field_status:J", False),
    ("Llm/s;->field_totalLen:J", False),
    ("Llm/s;->field_offset:J", False),
    ("Llm/s;->field_isUpload:Z", False),
    ("Llm/s;->field_fileFullPath:Ljava/lang/String;", False),
]
METHODS = [
    ("Llu/o0;-><init>(Ljava/lang/String;Ljava/lang/String;)V", False),
    ("Llu/o0;->n(Ljava/lang/String;)V", False),
    ("Llt5/v;->c(Llu/o0;)V", False),
    ("Llu/m0;->h()Lhr3/i0;", False),
    ("Llm/d8;->getMsgId()J", False),
    ("Llm/d8;->O0()Ljava/lang/String;", False),
    ("Llm/d8;->G0()J", False),
    ("Llm/d8;->N0()I", False),
    ("Llm/d8;->A0()I", False),
    ("Lcom/tencent/mm/pluginsdk/model/app/d;-><init>()V", False),
    ("Lcom/tencent/mm/pluginsdk/model/app/v5;->Ej()Lcom/tencent/mm/pluginsdk/model/app/e;", True),
    ("Lgg5/n0;->get(Lgg5/f0;[Ljava/lang/String;)Z", False),
    ("Lyx0/s;->a(Ljava/lang/String;)Z", False),
    ("Lyx0/j0;->k(Ljava/lang/String;J)Lcom/tencent/mm/storage/f9;", False),
    ("Lcom/tencent/mm/vfs/z6;->E(Ljava/lang/String;)Ljava/io/InputStream;", True),
]
RELATIONS = [
    ("Llu/o0;-><init>(Ljava/lang/String;Ljava/lang/String;)V", "Llu/o0;->q:Ljava/lang/String;", "put"),
    ("Llu/o0;-><init>(Ljava/lang/String;Ljava/lang/String;)V", "Llu/o0;->r:Ljava/lang/String;", "put"),
    ("Llu/o0;->n(Ljava/lang/String;)V", "Llu/o0;->s:Ljava/lang/String;", "put"),
    ("Llu/m0;->h()Lhr3/i0;", "Llu/m0;->o:Llu/o0;", "get"),
    ("Llu/m0;->h()Lhr3/i0;", "Lhr3/i0;->g:Lcom/tencent/mm/plugin/msg/MsgIdTalker;", "put"),
    ("Llm/d8;->getMsgId()J", "Llm/d8;->field_msgId:J", "get"),
    ("Llm/d8;->O0()Ljava/lang/String;", "Llm/d8;->field_talker:Ljava/lang/String;", "get"),
    ("Llm/d8;->G0()J", "Llm/d8;->field_msgSvrId:J", "get"),
    ("Llm/d8;->N0()I", "Llm/d8;->field_status:I", "get"),
    ("Llm/d8;->A0()I", "Llm/d8;->field_isSend:I", "get"),
]


def checks(client, smali):
    """Verify definitions plus meaning, using the shared complete-method decoder."""
    fields, methods, relations = client.query([
        "SELECT field_descriptor,access_flags FROM class_fields WHERE field_descriptor IN ("
        + sql_values(value for value, _ in FIELDS) + ")",
        "SELECT method_descriptor,access_flags FROM class_methods WHERE method_descriptor IN ("
        + sql_values(value for value, _ in METHODS) + ")",
        "SELECT method,field,using_type FROM method_field_accesses WHERE method IN ("
        + sql_values(sorted({value[0] for value in RELATIONS})) + ")",
    ])
    result = check_definitions(FIELDS, fields, "field_descriptor")
    result += check_definitions(METHODS, methods, "method_descriptor")
    for method, field, access in RELATIONS:
        result.append({
            "check": "file field semantics: " + method + " => " + field,
            "ok": any(row["method"] == method and row["field"] == field
                      and row["using_type"] == access for row in relations),
        })
    native = smali(client, "Llt5/v;->c(Llu/o0;)V")
    snapshot = smali(client, "Llu/m0;->h()Lhr3/i0;")
    status = smali(client, "Lhr3/k0;-><clinit>()V")
    completion = smali(client, "Llu/g0;->invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;")
    migration = smali(client, "Lyx0/s;->a(Ljava/lang/String;)Z")
    store = smali(client, "Lcom/tencent/mm/pluginsdk/model/app/v5;->Ej()Lcom/tencent/mm/pluginsdk/model/app/e;")
    lookup = smali(client, "Lyx0/j0;->k(Ljava/lang/String;J)Lcom/tencent/mm/storage/f9;")

    def sets_message_state(value):
        return bool(re.search(
            r"const/4 (v\d+), " + value + r"\s+invoke-virtual \{v\d+, \1\}, "
            r"Lcom/tencent/mm/storage/f9;->s1\(I\)V", completion))

    def sets_attachment_state(value):
        return bool(re.search(
            r"const-wide/16 (v\d+), " + value + r"\s+iput-wide \1, v\d+, "
            r"Llm/s;->field_status:J", completion))

    result += [
        {"check": "file controller submits through the native task and coroutine flow",
         "ok": all(value in native for value in [
             "Llu/o0;->q:Ljava/lang/String;", "Llu/o0;->r:Ljava/lang/String;",
             "Lfu/i;->Mj(Llu/o0;)Lhr3/f0;", "Lfu/g;->ek(Lhr3/f0;)Lkotlinx/coroutines/flow/j;",
         ])},
        {"check": "file snapshot carries the native message ID/talker pair",
         "ok": all(value in snapshot for value in [
             "Lcom/tencent/mm/plugin/msg/MsgIdTalker;-><init>(JLjava/lang/String;)V",
             "Lhr3/i0;->g:Lcom/tencent/mm/plugin/msg/MsgIdTalker;",
             "Lcom/tencent/mm/protobuf/e;->getLong(I)J",
             "Lcom/tencent/mm/protobuf/e;->getString(I)Ljava/lang/String;",
         ])},
        {"check": "native final phase still has the literal enum name Finish",
         "ok": '"Finish"' in status and "Lhr3/k0;->n:Lhr3/k0;" in status},
        {"check": "file success writes message status 2, server ID and attachment status 199",
         "ok": sets_message_state("0x2") and sets_attachment_state("0xc7")
               and "Llm/d8;->p1(J)V" in completion
               and "Llm/s;->field_offset:J" in completion
               and "Llm/s;->field_totalLen:J" in completion},
        {"check": "file failure writes message status 5 and attachment status 198",
         "ok": sets_message_state("0x5") and sets_attachment_state("0xc6")},
        {"check": "file completion checks preparation, upload and send phase results",
         "ok": all(value in completion for value in [
             "Lju/a;->m:Lhr3/j0;", "Llu/o0;->J:Llu/q0;",
             "Lju/a;->n:Lhr3/m0;", "Lju/a;->o:Lhr3/h0;", "Lhr3/o0;->a:I",
         ])},
        {"check": "attachment migration selector only reads the migrated flag",
         "ok": '"_migrated_3"' in migration
               and "Lcom/tencent/mm/sdk/platformtools/o4;->getBoolean(Ljava/lang/String;Z)Z" in migration
               and "->put" not in migration and "->set" not in migration},
        {"check": "attachment store accessor resolves the current native store",
         "ok": "Lbu/q0;->Ej()Lcu/g0;" in store and "Lcom/tencent/mm/pluginsdk/model/app/e;" in store},
        {"check": "native message lookup verifies both local message ID and talker",
         "ok": all(value in lookup for value in [
             "Llm/d8;->getMsgId()J", "Llm/d8;->O0()Ljava/lang/String;",
             "Lkotlin/jvm/internal/p;->c(Ljava/lang/Object;Ljava/lang/Object;)Z",
         ])},
    ]
    return result
