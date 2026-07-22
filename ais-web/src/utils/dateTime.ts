/**
 * Parse API datetime strings into a Date.
 * Backend emits ISO-8601 UTC (with Z). Legacy values without a zone are treated
 * as UTC so absolute instants stay consistent across clients.
 */
export function parseApiDate(value: string | number | Date | null | undefined): Date | null {
  if (value == null || value === '') return null
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value
  }
  if (typeof value === 'number') {
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? null : date
  }

  const raw = String(value).trim()
  if (!raw) return null

  // Epoch millis as string
  if (/^\d{10,13}$/.test(raw)) {
    const millis = raw.length === 10 ? Number(raw) * 1000 : Number(raw)
    const date = new Date(millis)
    return Number.isNaN(date.getTime()) ? null : date
  }

  // Already has timezone designator (Z or ±HH:MM)
  if (/[zZ]$/.test(raw) || /[+-]\d{2}:?\d{2}$/.test(raw)) {
    const date = new Date(raw)
    return Number.isNaN(date.getTime()) ? null : date
  }

  // "yyyy-MM-dd HH:mm:ss" or ISO without zone → treat as UTC
  const normalized = raw.includes('T') ? raw : raw.replace(' ', 'T')
  const date = new Date(`${normalized}Z`)
  return Number.isNaN(date.getTime()) ? null : date
}

/** Local calendar day key for grouping, e.g. "2026-07-22". */
export function formatLocalDateKey(value: string | number | Date | null | undefined): string {
  const date = parseApiDate(value)
  if (!date) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** Full local datetime string using the browser timezone. */
export function formatDateTime(value: string | number | Date | null | undefined, fallback = ''): string {
  const date = parseApiDate(value)
  if (!date) return fallback
  return date.toLocaleString('zh-CN', { hour12: false })
}

/** Compact local time (HH:mm) using the browser timezone. */
export function formatTimeHm(value: string | number | Date | null | undefined, fallback = ''): string {
  const date = parseApiDate(value)
  if (!date) return fallback
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

/** Local datetime with seconds. */
export function formatDateTimeSeconds(value: string | number | Date | null | undefined, fallback = ''): string {
  const date = parseApiDate(value)
  if (!date) return fallback
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** Relative-friendly session list time (today → time, else month/day). */
export function formatRelativeSessionTime(value: string | number | Date | null | undefined, fallback = ''): string {
  const date = parseApiDate(value)
  if (!date) return fallback
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  if (diff < 86400000 && diff >= 0) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
  }
  return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
