import { describe, expect, it, vi } from 'vitest'
import { playNativeMedia } from '../../e2e-integrated/native-media-support'

function player(kind: 'audio' | 'video' = 'video', overrides: Record<string, unknown> = {}) {
  const media = document.createElement(kind)
  const values = { error: null, ended: true, paused: true, readyState: 4, duration: 2, currentTime: 2,
    videoWidth: 160, videoHeight: 96, getVideoPlaybackQuality: () => ({ totalVideoFrames: 1 }), ...overrides }
  for (const [key, value] of Object.entries(values)) Object.defineProperty(media, key, { configurable: true, value })
  vi.spyOn(media, 'play').mockRejectedValue(new DOMException('Synthetic end ordering', 'AbortError'))
  return media
}

// These doubles verify the exception boundary; actual decoding is tested only
// by the separate real-backend Playwright journeys.
describe('native playback evidence boundary', () => {
  it('awaits ordinary successful native playback', async () => {
    const media = player(); vi.mocked(media.play).mockResolvedValue()
    expect(await playNativeMedia(media)).toBe('playing'); expect(media.muted).toBe(true)
  })
  it.each(['audio', 'video'] as const)('accepts only evidenced natural %s completion after an AbortError', async kind => {
    expect(await playNativeMedia(player(kind))).toBe('naturally-ended-before-play-settled')
  })
  it.each(['NotAllowedError', 'NotSupportedError', 'NetworkError'])('never accepts %s', async name => {
    const media = player(), failure = new DOMException('Synthetic failure', name)
    vi.mocked(media.play).mockRejectedValue(failure)
    await expect(playNativeMedia(media)).rejects.toBe(failure)
  })
  it.each([
    { error: { code: 4 } }, { ended: false }, { paused: false }, { readyState: 1 },
    { duration: NaN }, { duration: Infinity }, { duration: 0 }, { currentTime: 0 }, { currentTime: 1 },
    { videoWidth: 0 }, { videoHeight: 0 }, { getVideoPlaybackQuality: () => ({ totalVideoFrames: 0 }) },
  ])('rejects an abort without complete native evidence: %j', async overrides => {
    await expect(playNativeMedia(player('video', overrides))).rejects.toMatchObject({ name: 'AbortError' })
  })
  it('rethrows arbitrary non-DOM failures', async () => {
    const media = player(), failure = new Error('Synthetic failure')
    vi.mocked(media.play).mockRejectedValue(failure)
    await expect(playNativeMedia(media)).rejects.toBe(failure)
  })
})
