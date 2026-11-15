import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sendFile, getMessageImage } from '../src/api.ts';
import { MAX_FILE_BYTES, MAX_IMAGE_BYTES, fileValidationError, sameFileScope, fileDraftMatchesScope, newFileRequestKey,
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


test('image draft preserves attachment kind across uncertain retries and bounds its metadata', () => {
  const scope = { device: 'd', ownerWxid: 'a', generation: 2, wxid: 'filehelper' };
  const draft = { kind: 'image', name: 'photo.PNG', size: MAX_IMAGE_BYTES, key: newFileRequestKey(), scope };
  const data = new Map();
  const storage = { setItem: (k,v) => data.set(k,v), getItem: k => data.get(k) };
  persistFileRequest(draft, storage);
  assert.equal(restoreFileRequest(storage).kind, 'image');
  assert.equal(restoreFileRequest(storage).key, draft.key);
  assert.equal(restoreFileRequest({ getItem: () => JSON.stringify({ ...draft, size: MAX_IMAGE_BYTES + 1 }) }), null);
  assert.equal(restoreFileRequest({ getItem: () => JSON.stringify({ ...draft, kind: 'video' }) }), null);
  assert.equal(fileValidationError({ name: '中文.PNG', size: MAX_IMAGE_BYTES }, 'image'), '');
  assert.match(fileValidationError({ name: 'photo.jpg', size: MAX_IMAGE_BYTES + 1 }, 'image'), /8 MiB/);
  assert.match(fileValidationError({ name: 'animation.gif', size: 10 }, 'image'), /JPEG 或 PNG/);
  assert.match(fileValidationError({ name: 'photo.jpg', size: 10 }), /图片或视频/);
});

test('image uploads reuse multipart and the original idempotency key at the image endpoint', async t => {
  const previousWindow = globalThis.window;
  const previousFetch = globalThis.fetch;
  t.after(() => { globalThis.window = previousWindow; globalThis.fetch = previousFetch; });
  globalThis.window = { location: { pathname: '/observatory/admin/' }, setTimeout: () => 1, clearTimeout: () => {} };
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/observatory/api/send/image');
    assert.equal(options.headers['Idempotency-Key'], 'original-key');
    assert.equal(options.body.get('file').name, '原图.png');
    assert.equal(options.body.get('wxid'), 'filehelper');
    return new Response(JSON.stringify({ ok: true, outbox_id: 2 }));
  };
  await sendFile({ password: 'mock', device: 'd', ownerWxid: 'a', accountGeneration: 1,
    wxid: 'filehelper', kind: 'image', file: new File(['png'], '原图.png'), idempotencyKey: 'original-key' });
});

test('incoming images use the fixed private route, header auth, and bounded response bytes', async t => {
  const previousWindow = globalThis.window;
  const previousFetch = globalThis.fetch;
  t.after(() => { globalThis.window = previousWindow; globalThis.fetch = previousFetch; });
  globalThis.window = { location: { pathname: '/observatory/admin/' } };
  let calls = 0;
  globalThis.fetch = async (url, options) => {
    calls++;
    assert.equal(url, '/observatory/api/media/images/' + 'a'.repeat(32));
    assert.equal(options.headers['X-Bridge-Password'], 'mock-private-password');
    assert.equal(options.redirect, 'error');
    return new Response('bytes', { headers: { 'content-type': 'image/png' } });
  };
  const signal = new AbortController().signal;
  const path = '/api/media/images/' + 'a'.repeat(32);
  for (const bad of ['https://evil.example' + path, '//' + 'evil.example' + path, path + '?password=leak', '/api/api-keys']) {
    await assert.rejects(getMessageImage(bad, 'mock-private-password', signal), /图片地址无效/);
  }
  assert.equal(calls, 0);
  const blob = await getMessageImage(path, 'mock-private-password', signal);
  assert.equal(blob.type, 'image/png');
  assert.equal(await blob.text(), 'bytes');
  globalThis.fetch = async () => new Response('x', { headers: { 'content-type': 'text/html' } });
  await assert.rejects(getMessageImage(path, 'mock', signal), /图片格式/);
  globalThis.fetch = async () => new Response('x', { headers: { 'content-type': 'image/png', 'content-length': String(MAX_IMAGE_BYTES + 1) } });
  await assert.rejects(getMessageImage(path, 'mock', signal), /大小限制/);
  globalThis.fetch = async () => new Response(new Uint8Array(MAX_IMAGE_BYTES + 1), { headers: { 'content-type': 'image/png' } });
  await assert.rejects(getMessageImage(path, 'mock', signal), /大小限制/);
});
