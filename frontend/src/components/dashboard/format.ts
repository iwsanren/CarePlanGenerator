/**
 * Pure formatting helpers for the dashboard. Chart libraries call formatters from inside their own
 * rendering (axis ticks, tooltips), which a jsdom test never triggers; keeping them here as plain
 * functions lets them be tested directly, and keeps no inline callbacks in the JSX.
 */

export const NO_DATA = 'No data'

/** 0.1429 -> "14.3%"; null (nothing to measure) -> "—", never "0%". */
export function formatPercent(value: number | null): string {
    return value === null ? '—' : `${(value * 100).toFixed(1)}%`
}

/** Axis tick for a 0..1 scale: 0.25 -> "25%". */
export function formatPercentTick(value: number): string {
    return `${Math.round(value * 100)}%`
}

/** 12.34 -> "12.3 s", 125 -> "2 min 5 s"; null (no timing data) -> "No data", never "0 s". */
export function formatSeconds(value: number | null): string {
    if (value === null) return NO_DATA
    const rounded = Math.round(value * 10) / 10
    if (rounded < 60) return `${rounded.toFixed(1)} s`
    const total = Math.round(value)
    return `${Math.floor(total / 60)} min ${total % 60} s`
}

/** "2026-11-08" -> "Nov 8". The day is a UTC calendar day, so format it in UTC (no local-time shift). */
export function formatShortDate(isoDate: string): string {
    return new Date(`${isoDate}T00:00:00Z`).toLocaleDateString('en-US', {
        month: 'short',
        day: 'numeric',
        timeZone: 'UTC',
    })
}

/** Names of the two trend series, also used by the tooltip to pick the right unit. */
export const TREND_SECONDS_NAME = 'Avg generation time'
export const TREND_RATE_NAME = 'Failure rate'

/** Tooltip text for the trend chart: seconds for the solid line, a percentage for the dashed one. */
export function formatTrendTooltip(value: unknown, name: unknown): string {
    const number = typeof value === 'number' ? value : null
    return name === TREND_RATE_NAME ? formatPercent(number) : formatSeconds(number)
}
