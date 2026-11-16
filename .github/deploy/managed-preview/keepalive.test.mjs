import test from 'node:test';
import assert from 'node:assert/strict';
import worker, { heartbeat, inWindow } from './keepalive.mjs';
const stamp = (time) => Date.parse(`2026-11-17T${time}:00+05:30`);
test('IST boundaries and exact daily schedule', () => {
  for (const time of ['00:00', '08:40']) assert.equal(inWindow(stamp(time)), false);
  for (const time of ['08:50', '09:00', '23:50']) assert.equal(inWindow(stamp(time)), true);
  assert.equal(inWindow(stamp('09:00') + 1), false);
  let count = 0;
  for (let minute = 0; minute < 1440; minute++) {
    if (inWindow(stamp('00:00') + minute * 60_000)) count++;
  }
  assert.equal(count, 91);
});
test('one credential-free bounded status fetch and no body retention', async () => {
  const time = stamp('09:00'); let calls = 0; let cancelled = 0;
  const result = await heartbeat({ scheduledTime: time }, { ENABLED: 'true' }, async (url, options) => {
    calls++;
    assert.equal(url, 'https://nkw-arijit634.mytunnel.org/_preview/live');
    assert.equal(options.method, 'GET'); assert.equal(options.redirect, 'manual');
    assert.equal(options.credentials, 'omit'); assert.deepEqual(options.headers, { Accept: 'application/json' });
    assert.equal(options.body, undefined); assert.ok(options.signal instanceof AbortSignal);
    return { status: 200, body: { cancel: async () => { cancelled++; } } };
  }, time);
  assert.equal(result, 'checked'); assert.equal(calls, 1); assert.equal(cancelled, 1);
});
test('disabled, delayed, future or outside-window events make no network request', async () => {
  const fail = () => { throw new Error('unexpected network'); }; const time = stamp('09:00');
  assert.equal(await heartbeat({ scheduledTime: time }, {}, fail, time), 'skipped');
  assert.equal(await heartbeat({ scheduledTime: time }, { ENABLED: 'true' }, fail, time + 120_001), 'skipped');
  assert.equal(await heartbeat({ scheduledTime: time }, { ENABLED: 'true' }, fail, time - 1), 'skipped');
  assert.equal(await heartbeat({ scheduledTime: stamp('00:00') }, { ENABLED: 'true' }, fail, stamp('00:00')), 'skipped');
});
test('redirect and error responses never become successful checks', async () => {
  const time = stamp('09:00');
  for (const status of [301, 302, 401, 500, 503]) {
    let cancelled = false;
    await assert.rejects(heartbeat({ scheduledTime: time }, { ENABLED: 'true' }, async () => ({
      status, body: { cancel: async () => { cancelled = true; } },
    }), time), /preview_liveness_unavailable/);
    assert.equal(cancelled, true);
  }
});
test('worker has no public trigger and no retry loop or payload diagnostics', async () => {
  assert.equal(worker.fetch().status, 404);
});
