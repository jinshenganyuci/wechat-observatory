#!/usr/bin/env python3
"""Isolated Observatory synthetic-phone harness. Never uses a real phone.

All network calls are pinned to 127.0.0.1:18189. The only SQL database this
launcher can create/use is observatory_hermes_test. No provider secrets are read.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = Path(__file__).resolve().parents[3]
WORKSPACE = REPO.parent.parent
LAB = WORKSPACE / "work/hermes-lab"
PRIVATE = LAB / "private"
CONFIG = PRIVATE / "observatory.json"
PHONE_STATE = PRIVATE / "observatory-phone.json"
EVENTS = PRIVATE / "observatory-events.json"
CURSOR = PRIVATE / "observatory-sse-cursor.json"
TRACE = LAB / "evidence/observatory-trace.jsonl"
GO = "/tmp/observatory-go/go/bin/go"
HOST, PORT = "127.0.0.1", 18189
BASE = "http://127.0.0.1:18189"
DATABASE = "observatory_hermes_test"
DEVICE, OWNER = "hermes-lab", "wxid_hermes_bot"
ADMIN, USER = "wxid_hermes_admin", "wxid_hermes_user"
STRANGER, ROOM = "wxid_hermes_stranger", "12345@chatroom"
RECIPIENTS = {ADMIN, USER, STRANGER, "filehelper", ROOM}
MAX_SIMULATED_FILE_BYTES = 1023
STOP = False

VECTORS = {
    "turn1": {"actor": ADMIN, "text": "这是隔离通道测试。记住口令 blue-orchid，只回复已记住。"},
    "turn2": {"actor": ADMIN, "text": "刚才要求记住的口令是什么？只回答口令。"},
    "new": {"actor": ADMIN, "text": "/new"},
    "model": {"actor": ADMIN, "text": "/model gpt-6-luna --session"},
    "user": {"actor": USER, "text": "这是隔离普通用户测试，只回复 USER-OK。"},
    "unauthorized": {"actor": STRANGER, "text": "不应到达模型：STRANGER-NO-LLM"},
    "group": {"actor": ADMIN, "room": ROOM, "text": "不应到达模型：GROUP-NO-LLM"},
    "self": {"actor": OWNER, "direction": "sent", "peer": ADMIN, "text": "不应到达模型：SELF-NO-LLM"},
}

class LabError(Exception):
    pass

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise LabError("redirect_refused")

OPENER = urllib.request.build_opener(NoRedirect)

def private_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    temp = path.with_name(path.name + ".new")
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.flush(); os.fsync(output.fileno())
    os.replace(temp, path)

def trace(event, **fields):
    TRACE.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(TRACE, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    row = dict(event=event, at=datetime.now(timezone.utc).isoformat(), **fields)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        output.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(json.dumps(row, ensure_ascii=False), flush=True)

def config():
    value = json.loads(CONFIG.read_text())
    if value.get("base_url") != BASE or value.get("database") != DATABASE or value.get("device") != DEVICE or value.get("owner_wxid") != OWNER:
        raise LabError("unexpected_lab_scope")
    return value

def headers(admin=False, extra=None):
    result = {"Content-Type": "application/json"}
    if admin: result["X-Bridge-Password"] = config()["admin_password"]
    if extra: result.update(extra)
    return result

def request(path, data=None, admin=False, extra=None, timeout=15):
    if not path.startswith("/") or path.startswith("//") or "://" in path:
        raise LabError("nonlocal_path_refused")
    config()  # Validate the private scope before every network operation.
    body = None if data is None else json.dumps(data, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + path, data=body, headers=headers(admin, extra))
    try:
        with OPENER.open(req, timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        try: code = json.load(error).get("code", "http_error")
        except Exception: code = "http_error"
        raise LabError("http_%d_%s" % (error.code, code)) from None
    except urllib.error.URLError:
        raise LabError("lab_backend_unreachable") from None

def environment():
    cfg = config()
    # Only the established local MySQL administrator password is read here.
    # It remains in the child environment, never a command argument or log.
    credentials = json.loads((WORKSPACE / "work/local-test/credentials.json").read_text())
    dsn = "root:" + credentials["root_password"] + "@unix(/tmp/observatory-phone-test/mysql.sock)/" + DATABASE + "?parseTime=true&charset=utf8mb4&loc=UTC"
    env = {k: v for k, v in os.environ.items() if not k.startswith("BRIDGE_")}
    env.update(BRIDGE_HTTP_ADDR=HOST+":"+str(PORT), BRIDGE_ADMIN_PASSWORD=cfg["admin_password"],
               BRIDGE_DEFAULT_DEVICE=DEVICE, BRIDGE_INSTANCE_ID="hermes-isolated-lab",
               BRIDGE_MYSQL_DSN=dsn, BRIDGE_MYSQL_AUTO_MIGRATE="false",
               BRIDGE_DEVICES=DEVICE+"|"+OWNER+"|Hermes Synthetic Lab",
               BRIDGE_API_KEYS=cfg["module_api_key"]+"|"+DEVICE+"|Synthetic phone",
               BRIDGE_EVENT_IDENTITY_V2_DEVICES=DEVICE,
               BRIDGE_FILE_SPOOL_DIR=str(LAB/"observatory-files"),
               GOCACHE="/tmp/observatory-go-cache", GOMODCACHE="/tmp/observatory-go-mod")
    return env

def prepare():
    if not CONFIG.exists():
        private_json(CONFIG, dict(base_url=BASE,database=DATABASE,device=DEVICE,owner_wxid=OWNER,
            admin_wxid=ADMIN,allowed_wxids=[ADMIN,USER],admin_password=secrets.token_urlsafe(32),
            module_api_key=secrets.token_urlsafe(32),instance_id="lab-install-"+secrets.token_hex(16),
            account_session="lab-session-"+secrets.token_hex(16),account_generation=1))
    env=environment();bindir=LAB/"observatory-bin";bindir.mkdir(exist_ok=True)
    for binary in ("bridge","bridge-db"):
        subprocess.run([GO,"build","-o",str(bindir/binary),"./cmd/"+binary],cwd=REPO,env=env,check=True)
    completed=subprocess.run([str(bindir/"bridge-db"),"-create-database","-migrate","-seed","-check"],
        cwd=REPO,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    if completed.returncode: raise LabError("isolated_database_prepare_failed")
    result=json.loads(completed.stdout)
    if result.get("ready") is not True or result.get("default_device")!=DEVICE: raise LabError("isolated_database_not_ready")
    trace("prepared",base_url=BASE,database=DATABASE,device=DEVICE,identity_v2=True)

def serve():
    binary=LAB/"observatory-bin/bridge"
    if not binary.exists(): raise LabError("run_prepare_first")
    os.execve(str(binary),[str(binary)],environment())

def register():
    c=config()
    result=request("/module/register",dict(api_key=c["module_api_key"],device=DEVICE,wxid=OWNER,
        instance_id=c["instance_id"],account_session=c["account_session"],account_generation=c["account_generation"],
        nickname="Synthetic Hermes Bot",module_version="hermes-lab-simulator",device_model="Synthetic",wechat_version="simulated"))
    r=result.get("result",{})
    if result.get("ok") is not True or r.get("account_session")!=c["account_session"] or r.get("account_generation",0)<=0:
        raise LabError("registration_scope_mismatch")
    state=json.loads(PHONE_STATE.read_text()) if PHONE_STATE.exists() else {}
    state.update(generation=r["account_generation"],session=c["account_session"],acks=state.get("acks",{}))
    private_json(PHONE_STATE,state)
    trace("registered",device=DEVICE,generation=r["account_generation"])
    return state

def validate_delivery(item):
    if item.get("device") != DEVICE or item.get("owner_wxid") != OWNER or item.get("wxid") not in RECIPIENTS:
        raise LabError("unexpected_simulated_delivery_scope")
    if item.get("kind", "text") not in ("text", "file"):
        raise LabError("unexpected_simulated_delivery_kind")

def file_operation(item, operation):
    c = config()
    data = dict(api_key=c["module_api_key"], device=DEVICE, wxid=OWNER,
                account_session=c["account_session"], id=item["id"], lease_token=item["lease_token"])
    if operation == "begin": data["sha256"] = item["attachment"]["sha256"]
    return request("/module/outbox/file/" + operation, data)

def validate_small_file(item):
    validate_delivery(item)
    a = item.get("attachment", {})
    name, size, digest = a.get("name", ""), a.get("size"), a.get("sha256", "")
    blocked = "jpg jpeg bmp png tif tiff heic heif webp avi flv m4v mkv mov mp4 mts webm wmv".split()
    if (item.get("kind") != "file" or not item.get("lease_token") or not name or
        len(name.encode()) > 240 or any(c in name for c in "/\\") or
        any(ord(c) < 32 for c in name) or Path(name).suffix[1:].lower() in blocked or
        type(size) is not int or not 0 < size <= MAX_SIMULATED_FILE_BYTES or
        len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest)):
        raise LabError("only_small_ordinary_synthetic_files_allowed")
    return a

def download_small_file(item):
    a = validate_small_file(item)
    c = config()
    body = dict(api_key=c["module_api_key"], device=DEVICE, wxid=OWNER,
                account_session=c["account_session"], id=item["id"], lease_token=item["lease_token"])
    req = urllib.request.Request(BASE + "/module/outbox/file/download", data=json.dumps(body).encode(), headers=headers())
    with OPENER.open(req, timeout=10) as response:
        if response.headers.get("Content-Length") != str(a["size"]) or response.headers.get("X-Content-SHA256") != a["sha256"]:
            raise LabError("synthetic_download_header_mismatch")
        content = response.read(MAX_SIMULATED_FILE_BYTES + 1)
    if len(content) != a["size"] or hashlib.sha256(content).hexdigest() != a["sha256"]:
        raise LabError("synthetic_download_hash_mismatch")
    trace("synthetic_file_downloaded", task_id=item["id"], size=len(content), sha256=a["sha256"])

def ack_file(item, record, state, status):
    c = config()
    ack = dict(id=item["id"], status=status, lease_token=item["lease_token"],
               chat_record_id=record.get("chat_record_id", 0))
    if status == "unknown": ack["error"] = "Synthetic simulator interrupted before durable completion; never resubmitted"
    result = request("/module/outbox/ack", dict(api_key=c["module_api_key"], device=DEVICE, wxid=OWNER,
                     account_session=c["account_session"], items=[ack]))
    if result.get("ok") is not True or not any(x.get("id") == item["id"] and x.get("status") == status for x in result.get("items", [])):
        raise LabError("synthetic_file_ack_rejected")
    record["acknowledged"] = True
    private_json(PHONE_STATE, state)
    trace("synthetic_file_ack", task_id=item["id"], status=status, actual_wechat_send=False)

def recover_file(item, record, state):
    """A durable intent is never submitted again, including after response loss."""
    if record["phase"] == "completed":
        ack_file(item, record, state, "sent")
        return
    remote = file_operation(item, "status")["item"]
    validate_small_file(remote)
    if remote.get("native_started_at"):
        record["phase"] = "unknown"
        private_json(PHONE_STATE, state)
        ack_file(remote, record, state, "unknown")
    else:
        result = file_operation(remote, "abandon")
        if result.get("ok") is not True or result.get("item", {}).get("status") != "cancelled":
            raise LabError("synthetic_intent_not_safely_cancelled")
        record.update(phase="cancelled", acknowledged=True)
        private_json(PHONE_STATE, state)
        trace("synthetic_file_cancelled", task_id=item["id"])

def deliver_file(item, state):
    attachment = validate_small_file(item)
    ident = str(item["id"])
    digest = hashlib.sha256(json.dumps([item["wxid"], attachment, item.get("created_at")], sort_keys=True).encode()).hexdigest()
    old = state["acks"].get(ident)
    if old:
        if old.get("digest") != digest or old.get("kind") != "file": raise LabError("reused_outbox_identity")
        recover_file(old["item"], old, state)
        return
    file_operation(item, "renew")
    download_small_file(item)
    # This is only a protocol simulation. There is no WeChat/native submission.
    record = dict(kind="file", digest=digest, item=item, phase="intent", acknowledged=False)
    state["acks"][ident] = record
    private_json(PHONE_STATE, state)
    grant = file_operation(item, "begin")
    if grant.get("ok") is not True or grant.get("granted") is not True:
        recover_file(item, record, state)
        return
    record.update(phase="completed", chat_record_id=900000 + item["id"])
    private_json(PHONE_STATE, state)
    trace("synthetic_file_grant_consumed", task_id=item["id"], size=attachment["size"], actual_wechat_send=False)
    ack_file(item, record, state, "sent")

def phone(duration):
    global STOP
    for sig in (signal.SIGTERM,signal.SIGINT): signal.signal(sig,lambda *_:setattr(sys.modules[__name__],"STOP",True))
    state=register();start=time.monotonic();last_register=start
    for record in list(state["acks"].values()):
        if record.get("kind") == "file" and not record.get("acknowledged"):
            recover_file(record["item"], record, state)
    while not STOP and (duration<=0 or time.monotonic()-start<duration):
        c=config()
        if time.monotonic()-last_register>=60: state=register();last_register=time.monotonic()
        result=request("/module/outbox/poll",dict(api_key=c["module_api_key"],device=DEVICE,wxid=OWNER,account_session=c["account_session"],limit=1,file_protocol=1))
        for item in result.get("items",[]):
            validate_delivery(item)
            if item.get("kind") == "file":
                deliver_file(item, state)
                continue
            ident=str(item["id"]);digest=hashlib.sha256(json.dumps([item["wxid"],item["text"],item.get("created_at")],ensure_ascii=False).encode()).hexdigest()
            old=state["acks"].get(ident);replayed=old is not None
            if old and old["digest"]!=digest: raise LabError("reused_outbox_identity")
            if not old:
                old=dict(digest=digest,chat_record_id=900000+item["id"]);state["acks"][ident]=old;private_json(PHONE_STATE,state)
                trace("synthetic_delivery",task_id=item["id"],recipient=item["wxid"],text_chars=len(item["text"]),text_sha256=hashlib.sha256(item["text"].encode()).hexdigest())
            ack=request("/module/outbox/ack",dict(api_key=c["module_api_key"],device=DEVICE,wxid=OWNER,account_session=c["account_session"],items=[dict(id=item["id"],status="sent",chat_record_id=old["chat_record_id"])]))
            if ack.get("ok") is not True or not any(x.get("id")==item["id"] and x.get("status")=="sent" for x in ack.get("items",[])): raise LabError("synthetic_ack_rejected")
            trace("synthetic_ack",task_id=item["id"],replayed_delivery=replayed)
        time.sleep(1)
    trace("phone_stopped")

def make_event(case,ident,created):
    vector=VECTORS[case];sent=vector.get("direction")=="sent";room=vector.get("room","")
    peer=room or vector.get("peer",vector["actor"])
    return dict(id=str(ident),event_id=ident,chat_record_id=ident,device=DEVICE,owner_wxid=OWNER,
        **{"from":OWNER if sent else peer,"to":peer if sent else OWNER},room_id=room,
        sender=vector["actor"] if room else "",chat_id=peer,chat_kind="room" if room else "direct",
        text=vector["text"],message_type=1,direction="sent" if sent else "recv",create_time=created)

def emit(case,repeat=False):
    c=config();events=json.loads(EVENTS.read_text()) if EVENTS.exists() else {}
    if repeat:
        if case not in events: raise LabError("emit_case_before_repeat")
        event=events[case]
    else:
        event=make_event(case,time.time_ns()//1000000,int(time.time()));events[case]=event;private_json(EVENTS,events)
    payload=dict(event,api_key=c["module_api_key"],account_session=c["account_session"])
    result=request("/webhook/lsposed/message",payload)
    if result.get("ok") is not True or result.get("result",{}).get("published") is not True: raise LabError("event_not_durable")
    trace("event_submitted",case=case,source_id=event["id"],duplicate=repeat)
    return event

def sse(cursor=None,count=1,seconds=30,resume=False):
    config();path="/api/live/events?device="+DEVICE
    if resume and cursor is None and CURSOR.exists():
        saved=json.loads(CURSOR.read_text())
        if saved.get("device")!=DEVICE or saved.get("owner_wxid")!=OWNER:raise LabError("unexpected_cursor_scope")
        cursor=saved["cursor"]
    if cursor is not None and (not isinstance(cursor,int) or cursor<0):raise LabError("invalid_cursor")
    extra={}
    if cursor is not None: extra["Last-Event-ID"]=str(cursor)
    req=urllib.request.Request(BASE+path,headers=headers(True,extra))
    start=time.monotonic();seen=0;event={};data=[]
    with OPENER.open(req,timeout=25) as response:
        while time.monotonic()-start<seconds:
            raw=response.readline()
            if not raw: break
            line=raw.decode().rstrip("\r\n")
            if not line:
                if data:
                    payload=json.loads("\n".join(data));kind=event.get("event")
                    if kind=="ready":
                        if cursor is None:private_json(CURSOR,dict(device=DEVICE,owner_wxid=OWNER,cursor=payload["cursor"]))
                        trace("sse_ready",cursor=payload.get("cursor"))
                    elif kind=="message":
                        if payload.get("device")!=DEVICE or payload.get("owner_wxid")!=OWNER: raise LabError("unexpected_sse_scope")
                        private_json(CURSOR,dict(device=DEVICE,owner_wxid=OWNER,cursor=int(event["id"])))
                        trace("sse_message",cursor=int(event["id"]),event_key=payload.get("event_key"),direction=payload.get("direction"),source_id=payload.get("id"))
                        seen+=1
                        if seen>=count:return
                    elif kind=="error":raise LabError("sse_error")
                event={};data=[];continue
            name,_,value=line.partition(":");value=value.lstrip(" ")
            if name=="data":data.append(value)
            else:event[name]=value
    trace("sse_done",messages=seen)

def history(after=0):
    query=urllib.parse.urlencode(dict(device=DEVICE,owner_wxid=OWNER,after_id=after,limit=500))
    rows=request("/api/messages?"+query,admin=True).get("messages",[])
    for row in rows:
        if row.get("device")!=DEVICE or row.get("owner_wxid")!=OWNER:raise LabError("unexpected_history_scope")
    return rows

def outbox(task_id=None):
    """Read-only synthetic scope inspection; callers may inspect synthetic text."""
    query = urllib.parse.urlencode(dict(device=DEVICE, owner_wxid=OWNER, status="all", limit=100))
    if task_id is None:
        rows = request("/api/outbox?" + query, admin=True).get("items", [])
    else:
        if type(task_id) is not int or task_id <= 0: raise LabError("invalid_task_id")
        rows = [request("/api/outbox/" + str(task_id) + "?" + query, admin=True)["item"]]
    for row in rows: validate_delivery(row)
    return rows

def file_check():
    """Upload one fixed <1 KiB synthetic file, then only observe its original ID."""
    c = config(); state = json.loads(PHONE_STATE.read_text())
    boundary = "observatory-lab-" + secrets.token_hex(16)
    fields = dict(device=DEVICE, owner_wxid=OWNER, account_generation=str(state["generation"]), wxid="filehelper")
    parts = []
    for key, value in fields.items():
        parts.append(("--" + boundary + '\r\nContent-Disposition: form-data; name="' + key + '"\r\n\r\n' + value + '\r\n').encode())
    content = b"Synthetic Observatory file protocol fixture. No real WeChat send.\n"
    parts.append(("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="hermes-lab.txt"\r\nContent-Type: text/plain\r\n\r\n').encode() + content + b"\r\n")
    parts.append(("--" + boundary + "--\r\n").encode())
    req = urllib.request.Request(BASE + "/api/send/file", data=b"".join(parts), headers=headers(True, {
        "Content-Type": "multipart/form-data; boundary=" + boundary,
        "Idempotency-Key": "lab-file-" + secrets.token_hex(16)}))
    with OPENER.open(req, timeout=15) as response: result = json.load(response)
    if result.get("ok") is not True: raise LabError("synthetic_file_enqueue_failed")
    task_id = result["outbox_id"]
    trace("synthetic_file_enqueued", task_id=task_id, size=len(content))
    for _ in range(20):
        item = outbox(task_id)[0]
        if item.get("status") == "sent":
            if item.get("attempt_count") != 1: raise LabError("synthetic_file_multiple_leases")
            trace("synthetic_file_check_passed", task_id=task_id, status="sent", attempt_count=1, actual_wechat_send=False)
            return
        if item.get("status") in ("failed", "unknown", "cancelled"): raise LabError("synthetic_file_not_sent")
        time.sleep(.5)
    raise LabError("synthetic_file_not_yet_confirmed_no_retry")

def replay_check():
    before=history();cursor=max([x["id"] for x in before] or [0])
    event=emit("unauthorized");emit("unauthorized",True)
    after=history(cursor);matching=[x for x in after if x.get("source_id")==event["id"]]
    if len(matching)!=1 or not matching[0].get("event_key","").startswith("evt_v2_"):raise LabError("duplicate_not_deduplicated")
    trace("replay_check",cursor_before=cursor,persisted_event_id=matching[0]["id"],one_row=True)
    sse(cursor=cursor,count=len(after),seconds=5)

def lost_response():
    c=config();state=json.loads(PHONE_STATE.read_text());key="lab-response-loss-"+secrets.token_hex(12)
    body=dict(device=DEVICE,owner_wxid=OWNER,account_generation=state["generation"],wx_ids=[ADMIN],text="Synthetic idempotency response-loss check")
    conn=http.client.HTTPConnection(HOST,PORT,timeout=10)
    conn.request("POST","/api/send/text",body=json.dumps(body).encode(),headers=headers(True,{"Idempotency-Key":key}))
    response=conn.getresponse()
    if response.status!=200:conn.close();raise LabError("first_enqueue_not_accepted")
    # Intentionally discard the successful response body, then retry the exact
    # request. This is server/client uncertainty, never a native WeChat send.
    conn.close()
    retry=request("/api/send/text",body,True,{"Idempotency-Key":key})
    if retry.get("idempotent_replay") is not True:raise LabError("lost_response_created_second_task")
    trace("lost_response_recovered",task_id=retry["outbox_id"],idempotent_replay=True)

def status():
    rows=request("/api/modules/status",admin=True).get("modules",[])
    selected=[row for row in rows if row.get("device")==DEVICE]
    if len(selected)!=1 or selected[0].get("device_wxid")!=OWNER:raise LabError("lab_status_scope_mismatch")
    row=selected[0];trace("status",device=DEVICE,generation=row.get("account_generation"),runtime_status=row.get("runtime_status"))

def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest="command",required=True)
    for name in ("prepare","serve","register","status","replay-check","lost-response","file-check","vectors"):sub.add_parser(name)
    p=sub.add_parser("outbox");p.add_argument("--task-id",type=int)
    p=sub.add_parser("history");p.add_argument("--after",type=int,default=0)
    p=sub.add_parser("phone");p.add_argument("--duration",type=int,default=600)
    p=sub.add_parser("emit");p.add_argument("case",choices=VECTORS);p.add_argument("--repeat",action="store_true")
    p=sub.add_parser("sse");p.add_argument("--cursor",type=int);p.add_argument("--count",type=int,default=1);p.add_argument("--seconds",type=int,default=30);p.add_argument("--resume",action="store_true")
    args=parser.parse_args()
    if args.command=="prepare":prepare()
    elif args.command=="serve":serve()
    elif args.command=="register":register()
    elif args.command=="phone":phone(args.duration)
    elif args.command=="emit":emit(args.case,args.repeat)
    elif args.command=="sse":sse(args.cursor,args.count,args.seconds,args.resume)
    elif args.command=="replay-check":replay_check()
    elif args.command=="lost-response":lost_response()
    elif args.command=="file-check":file_check()
    elif args.command=="outbox":
        for row in outbox(args.task_id):trace("outbox_observed",task_id=row["id"],kind=row.get("kind","text"),recipient=row["wxid"],status=row["status"],attempt_count=row["attempt_count"],text_chars=len(row.get("text","")),attachment_size=row.get("attachment",{}).get("size"))
    elif args.command=="history":
        for row in history(args.after):trace("history_observed",sequence=row["id"],event_key=row.get("event_key"),direction=row.get("direction"),text_chars=len(row.get("text","")))
    elif args.command=="status":status()
    else:print(json.dumps(VECTORS,ensure_ascii=False,indent=2))

if __name__=="__main__":
    try:main()
    except (LabError,KeyboardInterrupt) as error:
        print(json.dumps(dict(error=str(error) if isinstance(error,LabError) else "interrupted")),file=sys.stderr)
        raise SystemExit(1)
