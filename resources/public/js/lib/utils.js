/**
 * Debounce a function call.
 */
export function debounce(fn, ms) {
  let timer
  return (...args) => {
    clearTimeout(timer)
    timer = setTimeout(() => fn(...args), ms)
  }
}

/**
 * Format a timestamp as relative time (e.g., "2d", "1w", "3mo").
 */
export function timeAgo(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  const now = Date.now()
  const sec = Math.floor((now - d.getTime()) / 1000)
  if (sec < 60) return 'now'
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}m`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr}h`
  const days = Math.floor(hr / 24)
  if (days < 7) return `${days}d`
  const weeks = Math.floor(days / 7)
  if (weeks < 5) return `${weeks}w`
  const months = Math.floor(days / 30)
  if (months < 12) return `${months}mo`
  return `${Math.floor(days / 365)}y`
}

/**
 * Map a weight value (0-5) to a CSS color.
 */
export function weightColor(w) {
  if (w == null) return 'var(--weight-low)'
  if (w < 0.5) return 'var(--weight-low)'
  if (w < 1.5) return 'var(--text-muted)'
  if (w < 2.5) return 'var(--weight-mid)'
  if (w < 3.5) return 'var(--weight-high)'
  return 'var(--weight-max)'
}

/**
 * Format a number with locale separators.
 */
export function formatNum(n) {
  if (n == null) return '0'
  return n.toLocaleString()
}

/**
 * Truncate text to N chars with ellipsis.
 */
export function truncate(s, n) {
  if (!s) return ''
  return s.length > n ? s.slice(0, n) + '\u2026' : s
}

/**
 * Deterministic hue from tag name (0-360).
 */
function hashStr(s) {
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = Math.imul(31, h) + s.charCodeAt(i) | 0
  }
  return Math.abs(h)
}

export function tagHue(name) {
  // Golden angle spread for maximum separation between any hues
  return (hashStr(name) * 137.508) % 360
}

export function tagColor(name) {
  const h = tagHue(name)
  const s = 55 + (hashStr(name) % 25) // 55-80%
  return `hsl(${h}, ${s}%, 68%)`
}

export function tagBg(name) {
  const h = tagHue(name)
  const s = 55 + (hashStr(name) % 25)
  return `hsla(${h}, ${s}%, 68%, 0.12)`
}

/**
 * Node type to CSS color variable.
 */
export function typeColor(type) {
  switch (type) {
    case 'fact': return 'var(--color-fact)'
    case 'session': return 'var(--color-session)'
    case 'blob': return 'var(--color-blob)'
    default: return 'var(--color-fact)'
  }
}
