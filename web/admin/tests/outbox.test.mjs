import { test } from 'node:test';
import assert from 'node:assert/strict';
import { outboxStatusLabel, outboxWaitText, outboxLeaseExpired } from '../src/outbox.ts';

test('pending and leased never imply confirmed send; terminal states are distinct', () => {
  assert.equal(outboxStatusLabel('pending'), '等待微信领取');
  assert.equal(outboxStatusLabel('leased'), '已分派 · 待回执');
  assert.equal(outboxStatusLabel('sent'), '微信已确认发送');
  assert.equal(outboxStatusLabel('failed'), '发送失败');
  assert.equal(outboxStatusLabel('failed', 'WeChat message queued but delivery unconfirmed; local msgId=42'), '结果未确认');
  assert.equal(outboxStatusLabel('failed', 'WeChat message send failed'), '发送失败');
  assert.equal(outboxStatusLabel('cancelled'), '已取消');
  assert.equal(outboxStatusLabel('mystery'), '状态未知');
});

test('wait age uses server time and only applies to active work', () => {
  const created_at = '2026-10-17T00:00:00Z';
  const now = Date.parse(created_at) + 125000;
  assert.equal(outboxWaitText({status: 'pending', created_at}, now), '已等待 2 分 5 秒');
  assert.equal(outboxWaitText({status: 'leased', created_at}, Date.parse(created_at) - 1), '已等待 0 秒');
  assert.equal(outboxWaitText({status: 'sent', created_at}, now), '');
  assert.equal(outboxWaitText({status: 'pending', created_at: 'invalid'}, now), '');
});

test('expired lease is recovery information, never a delivery ETA or proof of failure', () => {
  const lease_until = '2026-10-17T00:00:30Z';
  assert.equal(outboxLeaseExpired({status: 'leased', lease_until}, Date.parse(lease_until)), true);
  assert.equal(outboxLeaseExpired({status: 'leased', lease_until}, Date.parse(lease_until) - 1), false);
  assert.equal(outboxLeaseExpired({status: 'sent', lease_until}, Date.parse(lease_until)), false);
  assert.equal(outboxLeaseExpired({status: 'leased', lease_until: 'invalid'}, Date.now()), false);
});
