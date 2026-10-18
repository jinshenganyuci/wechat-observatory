import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sendFile } from '../src/api.ts';
import { MAX_FILE_BYTES, fileValidationError, sameFileScope, fileDraftMatchesScope, fileTargetSupported, newFileRequestKey,
  persistFileRequest, restoreFileRequest, clearFileRequest } from '../src/fileSend.ts';

test('ordinary file limit includes exactly 300 MiB and excludes media extensions case-insensitively', () => {
  assert.equal(MAX_FILE_BYTES, 300 * 1024 * 1024);
  assert.match(fileValidationError({ name: 'empty.txt', size: 0 }), /非空/);
  assert.equal(fileValidationError({ name: 'report.zip', size: MAX_FILE_BYTES }), '');
  assert.equal(fileValidationError({ name: 'report.zip', size: 26 * 1024 * 1024 }), '');
  assert.match(fileValidationError({ name: 'report.zip', size: MAX_FILE_BYTES + 1 }), /300 MiB/);
  for (const name of ['image.JPG', 'movie.Mp4', 'photo.heic']) {
    assert.match(fileValidationError({ name, size: 10 }), /图片或视频/);
  }
  assert.equal(fileValidationError({ name: 'report.pdf', size: 10 }), '');
  assert.equal(fileValidationError({ name: 'jpg', size: 10 }), '');
});

test('file upload has a 20-minute timeout and clears it after the mocked response', async t => {
  const previousWindow = globalThis.window;
  const previousFetch = globalThis.fetch;
  t.after(() => {
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
    globalThis.fetch = previousFetch;
  });
  let timeoutMs;
  let cleared;
  globalThis.window = { location: { pathname: '/' },
    setTimeout: (_callback, delay) => { timeoutMs = delay; return 42; },
    clearTimeout: handle => { cleared = handle; } };
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/send/file');
    assert.equal(options.body.get('file').size, 4);
    assert.equal(options.headers['Idempotency-Key'], 'mock-file-request');
    assert.equal(options.signal.aborted, false);
    return new Response(JSON.stringify({ ok: true, outbox_id: 1, idempotent_replay: false }));
  };
  await sendFile({ password: 'mock-password', device: 'd', ownerWxid: 'a', accountGeneration: 1,
    wxid: 'filehelper', file: new File(['mock'], 'small.txt'), idempotencyKey: 'mock-file-request' });
  assert.equal(timeoutMs, 20 * 60 * 1000);
  assert.equal(cleared, 42);
});

test('file names match the backend UTF-8 and Unicode contract', () => {
  for (const name of ['😀:报告.txt', 'x'.repeat(236) + '.txt', '中'.repeat(78) + '.txt']) {
    assert.equal(fileValidationError({ name, size: 1 }), '');
  }
  for (const name of ['', '.', '..', 'x'.repeat(237) + '.txt', '../report.txt', 'dir\\report.txt',
    '\u0085report.txt', 'report.txt\u00a0', 'repo\u0000rt.txt', '\ud800.txt']) {
    assert.match(fileValidationError({ name, size: 1 }), /文件名/);
  }
});

test('retry scope includes owner, generation, device and recipient', () => {
  const scope = { device: 'd', ownerWxid: 'a', generation: 2, wxid: 'friend' };
  assert.equal(sameFileScope(scope, { ...scope }), true);
  for (const [key, value] of Object.entries({ device: 'other', ownerWxid: 'b', generation: 3, wxid: 'room@chatroom' })) {
    assert.equal(sameFileScope(scope, { ...scope, [key]: value }), false);
  }
});

test('reload retains the original key without persisting file bytes or credentials', () => {
  const data = new Map();
  const storage = { setItem: (k,v) => data.set(k,v), getItem: k => data.get(k), removeItem: k => data.delete(k) };
  const draft = { name: 'report.txt', size: 4, file: { bytes: 'private bytes' }, key: newFileRequestKey(),
    scope: { device: 'd', ownerWxid: 'a', generation: 2, wxid: 'friend' }, stage: 'ready', message: '' };
  persistFileRequest(draft, storage);
  assert.equal([...data.values()][0].includes('private bytes'), false);
  const restored = restoreFileRequest(storage);
  assert.equal(restored.key, draft.key);
  assert.equal(restored.stage, 'unknown');
  assert.equal(restored.file, undefined);
  clearFileRequest(storage);
  assert.equal(restoreFileRequest(storage), null);
});

test('invalid stored retry identities are never used', () => {
  for (const raw of ['{', 'null', '{"name":"x","size":1,"key":"bad","scope":{}}']) {
    assert.equal(restoreFileRequest({ getItem: () => raw }), null);
  }
});

test('retry metadata accepts the 300 MiB boundary without allocating file bytes', () => {
  const draft = { name: 'report.zip', size: 300 * 1024 * 1024, key: newFileRequestKey(),
    scope: { device: 'd', ownerWxid: 'a', generation: 2, wxid: 'filehelper' } };
  assert.equal(restoreFileRequest({ getItem: () => JSON.stringify(draft) }).size, draft.size);
  assert.equal(restoreFileRequest({ getItem: () => JSON.stringify({ ...draft, size: draft.size + 1 }) }), null);
});

test('recovery permits original-generation replay but never a different account or recipient', () => {
  const scope = { device: 'd', ownerWxid: 'a', generation: 2, wxid: 'filehelper' };
  assert.equal(fileDraftMatchesScope({ scope, stage: 'ready' }, { ...scope, generation: 3 }), false);
  assert.equal(fileDraftMatchesScope({ scope, stage: 'unknown' }, { ...scope, generation: 3 }), true);
  assert.equal(fileDraftMatchesScope({ scope, stage: 'unknown' }, { ...scope, ownerWxid: 'b' }), false);
  assert.equal(fileDraftMatchesScope({ scope, stage: 'unknown' }, { ...scope, wxid: 'other@chatroom' }), false);
  assert.equal(fileTargetSupported('filehelper'), true);
  assert.equal(fileTargetSupported('notice@chatroom'), true);
  assert.equal(fileTargetSupported('friend'), false);
});

test('late success cannot erase a newer saved request', () => {
  const data = new Map();
  const storage = { setItem: (k,v) => data.set(k,v), getItem: k => data.get(k), removeItem: k => data.delete(k) };
  const draft = { name: 'report.txt', size: 4, key: newFileRequestKey(),
    scope: { device: 'd', ownerWxid: 'a', generation: 2, wxid: 'filehelper' } };
  persistFileRequest(draft, storage);
  clearFileRequest(storage, 'file-old');
  assert.equal(restoreFileRequest(storage).key, draft.key);
  clearFileRequest(storage, draft.key);
  assert.equal(restoreFileRequest(storage), null);
});
