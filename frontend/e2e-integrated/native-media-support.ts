/** Only tolerate the observed short-clip end/play-promise ordering, not decode,
 * transport, autoplay or interruption failures. The caller still asserts progress. */
export async function playNativeMedia(media: HTMLMediaElement) {
  media.muted = true
  try {
    await media.play()
    return 'playing' as const
  } catch (failure) {
    const completed = media.error === null && media.ended && media.paused
      && media.readyState >= 2 && Number.isFinite(media.duration) && media.duration > 0
      && media.currentTime >= media.duration
    const decoded = !(media instanceof HTMLVideoElement)
      || (media.videoWidth > 0 && media.videoHeight > 0 && media.getVideoPlaybackQuality().totalVideoFrames > 0)
    if (failure instanceof DOMException && failure.name === 'AbortError' && completed && decoded) {
      return 'naturally-ended-before-play-settled' as const
    }
    throw failure
  }
}
