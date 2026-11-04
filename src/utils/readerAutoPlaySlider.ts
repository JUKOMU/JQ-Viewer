const MIN_INTERVAL = 0.1
const MAX_INTERVAL = 30
const MAX_POSITION = 300

/** 中点保留当前方向；离开中点后，位置方向决定滚动方向。 */
export function readerAutoPlaySliderPosition(seconds: number): number {
  const magnitude = Math.abs(seconds)
  if (magnitude === MAX_INTERVAL) return 0
  const position = Math.round(
    ((MAX_INTERVAL - magnitude) / (MAX_INTERVAL - MIN_INTERVAL)) * MAX_POSITION,
  )
  return Math.sign(seconds) * position
}

export function readerAutoPlaySliderFill(seconds: number): Record<string, string> {
  const progress =
    ((readerAutoPlaySliderPosition(seconds) + MAX_POSITION) / (MAX_POSITION * 2)) * 100
  return {
    '--slider-fill-start': `${Math.min(progress, 50)}%`,
    '--slider-fill-end': `${Math.max(progress, 50)}%`,
  }
}

export function readerAutoPlayIntervalAtSliderPosition(
  position: number,
  currentSeconds: number,
): number {
  const direction = position === 0 ? Math.sign(currentSeconds) : Math.sign(position)
  const magnitude =
    position === 0
      ? MAX_INTERVAL
      : MAX_INTERVAL - (Math.abs(position) / MAX_POSITION) * (MAX_INTERVAL - MIN_INTERVAL)
  return Math.round(direction * magnitude * 100) / 100
}
