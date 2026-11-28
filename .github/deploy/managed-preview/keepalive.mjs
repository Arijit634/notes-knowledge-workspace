// Status-only heartbeat. It carries no application credentials or user data.
const LIVE_URL = 'https://nkw-arijit634.mytunnel.org/_preview/live';
const MAX_DELAY_MS = 120_000;
export function inWindow(milliseconds) {
  if (!Number.isFinite(milliseconds)) return false;
  const date = new Date(milliseconds + 330 * 60_000);
  const minute = date.getUTCHours() * 60 + date.getUTCMinutes();
  // Cloudflare scheduledTime can carry seconds/milliseconds within the cron minute.
  // Staleness is checked separately before dispatch; the approved window is minute-based.
  return minute === 530 || (minute >= 540 && minute <= 1430 && minute % 10 === 0);
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
  // Hosting startup/interstitial HTML can also return 200. Only the actual
  // status endpoint's JSON representation qualifies as a successful check.
  const type = response.headers?.get('Content-Type')?.split(';', 1)[0].trim().toLowerCase();
  if (response.status !== 200 || !['application/json', 'application/vnd.spring-boot.actuator.v3+json'].includes(type)) {
    throw new Error('preview_liveness_unavailable');
  }
  return 'checked';
}
export default {
  async scheduled(event, env) {
    try { await heartbeat(event, env); }
    catch { console.warn('preview_liveness_unavailable'); }
  },
  fetch() { return new Response(null, { status: 404 }); },
};
