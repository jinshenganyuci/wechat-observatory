# Isolated Observatory transport lab

`observatory_lab.py` runs the real Go backend with a synthetic phone. It never
connects to Android or to the live phone-test service. Its address and database
are pinned to `127.0.0.1:18189` and `observatory_hermes_test`; redirects are refused.
This is a developer lab, not an Android delivery or screen-off test.

The local launcher currently expects the workspace's established MySQL socket
and private administrator credential file. `prepare` creates only its dedicated
database and synthetic credentials, builds the current backend, and migrates and
seeds that database. It does not read any LLM provider credential.

From the repository root:

```sh
python3 -m unittest discover -s integrations/hermes/tests -p 'test_observatory_lab.py'
python3 integrations/hermes/tests/observatory_lab.py prepare
python3 integrations/hermes/tests/observatory_lab.py serve
# In a separate terminal:
python3 integrations/hermes/tests/observatory_lab.py phone --duration 10800
```

The private adapter configuration is in
`work/hermes-lab/private/observatory.json` relative to the outer task workspace.
It contains an admin password and module credential generated exclusively for
this lab. Do not commit or print it. The lab device is `hermes-lab`, logged-in
owner is `wxid_hermes_bot`, administrator actor is `wxid_hermes_admin`, and another
allowed actor is `wxid_hermes_user`. Only this test device enables event identity
v2. Configure the adapter allowlist explicitly before emitting accepted events.

The simulator registers a protected installation/session, polls once per second,
and ACKs synthetic deliveries as `sent`. It records each received task's
identity before ACK and recognizes re-delivery. It accepts only synthetic
recipients, including `wxid_hermes_stranger` so Hermes can send its native pairing
reply without invoking the LLM. This ACK means the simulator observed a task,
not that WeChat sent it.

For ordinary files strictly smaller than 1 KiB, it exercises the real file
protocol: renew lease, authenticated download, size/SHA-256 verification, durable
intent, one `begin` grant, durable simulated completion, and HTTP ACK. An
interrupted intent is reconciled or safely abandoned; it is never submitted
again. Media extensions and larger files are refused. No native WeChat code is
called and no downloaded fixture needs to be retained on disk.

```sh
python3 integrations/hermes/tests/observatory_lab.py status
python3 integrations/hermes/tests/observatory_lab.py replay-check
python3 integrations/hermes/tests/observatory_lab.py lost-response
python3 integrations/hermes/tests/observatory_lab.py file-check
python3 integrations/hermes/tests/observatory_lab.py outbox
python3 integrations/hermes/tests/observatory_lab.py outbox --task-id 2
python3 integrations/hermes/tests/observatory_lab.py history --after 0
python3 integrations/hermes/tests/observatory_lab.py emit turn1
python3 integrations/hermes/tests/observatory_lab.py emit turn2
python3 integrations/hermes/tests/observatory_lab.py emit new
python3 integrations/hermes/tests/observatory_lab.py emit model
python3 integrations/hermes/tests/observatory_lab.py emit user
python3 integrations/hermes/tests/observatory_lab.py emit unauthorized
python3 integrations/hermes/tests/observatory_lab.py emit group
python3 integrations/hermes/tests/observatory_lab.py emit self
python3 integrations/hermes/tests/observatory_lab.py emit turn1 --repeat
```

`replay-check` submits the same synthetic unauthorized inbound event twice, checks
one persisted v2 event, and reads it through `Last-Event-ID` replay. `lost-response`
discards a successful enqueue response body and retries with the exact original
key and payload; it must recover the original task. It never creates a replacement
request on error. Accepted commands and conversation checks require a running
Hermes adapter; the backend alone cannot prove model state, command execution,
allowlist enforcement, or absence of LLM calls for rejected events.

`file-check` uploads one fixed 66-byte TXT to the synthetic `filehelper`, then
only reads that original task until `sent / attempt_count=1` or a bounded failure.
It never retries an uncertain upload. The importable `outbox(task_id=None)` and
`history(after=0)` functions return full **synthetic-scope** records for assertions;
the CLI prints only metadata, not message text. Outbox lists are limited to the
latest 100; use the exact task ID for assertions.

`sse --cursor N --count 1` reads from an explicit sequence. `sse --resume --count 1`
uses the harness's durable `observatory-sse-cursor.json`, separate from the
adapter's own durable inbox state. This diagnostic consumer saves a cursor after
validating each event's scope; it does not execute messages or commands.

Evidence files under `work/hermes-lab/evidence/observatory-*` contain transport
metadata (IDs, hashes, lengths, timestamps), not credentials or message text.
Private generated inputs and simulated phone ACK state use mode `0600`.
Stop the launched server and simulator with SIGTERM when the lab is complete.

## Native shared-default integration tests

`test_shared_delivery.py` exercises the real, **unmodified** Hermes final-response
producer, delivery ledger, startup recovery, native authorization and session
routing against a synthetic bridge. It starts no real model, phone or Feishu
connection. The official CI targets are:

- v0.22.2: `d923a5eb487a17c0a68801bba9ef0f56c81040a8`
- Current deployment baseline: `6e9b3e3b8852f12bf965f888213470574b72803e`

No target applies an Observatory core patch. Plugin compatibility declarations
are broader than these concrete regression targets; they are not a claim that
all future upstream commits have passed. CI also checks that tracked Hermes
gateway sources stay unmodified.

Use a disposable test interpreter provisioned through Hermes PM with its declared
`messaging` and `feishu` extras. Do not pip/uv-install into a PM-managed production
runtime. From this repository root, use an unmodified Hermes checkout:

```sh
OBSERVATORY_SRC="$(pwd)"
HERMES_SRC="/absolute/path/to/stock-hermes-agent"
TEST_ROOT="$(mktemp -d)"
mkdir -p "$TEST_ROOT/os-home" "$TEST_ROOT/hermes-home"
cd "$HERMES_SRC"
env HOME="$TEST_ROOT/os-home" HERMES_HOME="$TEST_ROOT/hermes-home" \
  HERMES_RUNTIME_DIR="$TEST_ROOT/runtime" \
  python -m pm.build_env --source "$HERMES_SRC" --out "$TEST_ROOT/venv" \
  --extra messaging --extra feishu --group dev --group test

env HOME="$TEST_ROOT/os-home" HERMES_HOME="$TEST_ROOT/hermes-home" \
  HERMES_PYTHON="$TEST_ROOT/venv/bin/python" \
  bash scripts/run_tests.sh \
  "$OBSERVATORY_SRC/integrations/hermes/observatory/tests/test_plugin.py" \
  "$OBSERVATORY_SRC/integrations/hermes/tests/test_setup.py" \
  "$OBSERVATORY_SRC/integrations/hermes/tests/test_shared_delivery.py" \
  "$OBSERVATORY_SRC/integrations/hermes/tests/test_observatory_lab.py" \
  tests/gateway/test_delivery_ledger.py \
  tests/gateway/test_delivery_ledger_producer.py \
  tests/gateway/test_active_turn_recovery.py \
  tests/gateway/test_feishu*.py

git diff --exit-code HEAD -- gateway
```

The canonical runner isolates each test file and temporary Hermes home. The
shared tests check connection without a compatibility capability, first-send
acceptance, lost HTTP ACK using the same request key, distinct legitimate equal
texts, ingress replay deduplication, native authorization/session boundaries,
native reply recovery and unchanged Feishu recovery in one runner. They also
exercise an extreme accepted-before-crash case where native recovery can create
a second task: this is a documented upstream boundary, not hidden by a false
exactly-once assertion. Old pending rows retain their original payload/key and
become `needs_review`; connecting never scans them to resend.

Group cases feed synthetic room envelopes through the plugin intake, then check
native authorization, slash permissions, persisted SessionStore routing and the
final reply destination. An ordinary DM-paired member still cannot trigger a
group; an admitted administrator remains subject to native group authorization.
Room/sender labels are separate from IDs and appear as untrusted per-message
context. Session policy stays unchanged: with the current single administrator,
different groups and DMs remain separate; multi-admin sharing is not invented.

The shared tests also enter the real `hermes_cli.send_cmd.cmd_send` and
`cron.scheduler_delivery._standalone_send` routes, including upstream target
resolution, MEDIA extraction, filtering and `_send_to_platform` policy checks.
They replace bridge transport and test configuration, not the standalone sender
or the media capability gate. File-only and text-plus-file cases run on both
baselines: modern Hermes accepts both; stock v0.22.2 rejects file-only before
upload because it predates the public `StandaloneSendPolicy` contract.

Cron cases bind Hermes' real execution ContextVar and use the plugin's public
context reader. A retry with a known task receipt causes no second file submit;
a lost HTTP ACK reuses the original payload/key and creates one task even when
the upload request is repeated. A new execution is new work. Tests do not invent
a cron execution context for every upstream recovery path or claim that partial
file notices automatically reconstruct themselves after a crash.
The transport lab and its scope/credential guards above remain separate from
these mocked-transport SDK tests.

## Checking native Hermes behavior without editing its state

The isolated runtime wrapper uses `work/hermes-lab/home` as `HERMES_HOME`. Inspect
that home only. Open `state.db` using SQLite `mode=ro` and `PRAGMA query_only=ON`;
do not instantiate Hermes `SessionDB` just to inspect it, because that class can
perform migrations or maintenance.

- Read `gateway_routing.entry_json` only for `platform == "observatory"`, and
  extract `session_id`, `session_key`, and `model_override.model`. The two ordinary
  turns should keep one session; `/new` should change its session ID;
  `/model ... --session` should update that lane's model override. The legacy
  `sessions/sessions.json` mirror may lag, so the SQLite routing row is primary.
- For the selected synthetic session IDs, compare `sessions` fields `model`,
  `message_count`, `ended_at`, and `end_reason`, plus `messages` counts grouped by
  `role`. Content inspection, if needed, is limited to these synthetic sessions.
- Pairing records live in `platforms/pairing/observatory-pending.json` and
  `observatory-approved.json`, or the legacy `pairing/` directory according to
  Hermes layout. Check entry IDs/counts without printing pairing codes. Do not
  modify them to simulate a successful approval.
- The adapter's separate `observatory/*.sqlite3` database records its cursor,
  ingress event-key claims, and outbound intents. Compare counts and task IDs
  before/after exact event replay. A claimed ingress row alone does not prove an
  LLM call occurred; native session/message changes and bounded gateway evidence
  must agree. An unauthorized pairing reply is expected and is not an LLM reply.
