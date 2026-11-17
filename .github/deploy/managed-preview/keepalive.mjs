// Status-only heartbeat. It carries no application credentials or user data.
const LIVE_URL = 'https://nkw-arijit634.mytunnel.org/_preview/live';
const MAX_DELAY_MS = 120_000;
export function inWindow(milliseconds) {
  if (!Number.isFinite(milliseconds)) return false;
  const date = new Date(milliseconds + 330 * 60_000);
  const minute = date.getUTCHours() * 60 + date.getUTCMinutes();
  return date.getUTCSeconds() === 0 && date.getUTCMilliseconds() === 0 &&
    (minute === 530 || (minute >= 540 && minute <= 1430 && minute % 10 === 0));
}
export async function heartbeat(event, env, fetcher = fetch, now = Date.now()) {
  if (env.ENABLED !== 'true' || !inWindow(event.scheduledTime) ||
      now < event.scheduledTime || now - event.scheduledTime > MAX_DELAY_MS) return 'skipped';
  const response = await fetcher(LIVE_URL, {
    method: 'GET', redirect: 'manual', credentials: 'omit', cache: 'no-store',
    signal: AbortSignal.timeout(8_000), headers: { Accept: 'application/json' },
  });
  // Do not follow redirects or retain response bodies, including failures.
  if (response.body) await response.body.cancel();
  if (response.status !== 200) throw new Error('preview_liveness_unavailable');
  return 'checked';
}
export default {
  async scheduled(event, env) {
    try { await heartbeat(event, env); }
    catch { console.warn('preview_liveness_unavailable'); }
  },
  fetch() { return new Response(null, { status: 404 }); },
};
