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

/**
 * A duration given in seconds (that is what the API sends), shown in the unit that reads best:
 * 0.0066 -> "7 ms", 12.34 -> "12.3 s", 125 -> "2 min 5 s". The local mock LLM answers in a few
 * milliseconds, a real one in seconds, so one fixed unit would be wrong for one of them.
 * null (no timing data) -> "No data", never "0 ms".
 */
export function formatDuration(value: number | null): string {
    if (value === null) return NO_DATA
    const millis = Math.round(value * 1000)
    if (millis < 1000) return millis === 0 && value > 0 ? '<1 ms' : `${millis} ms`
    const rounded = Math.round(value * 10) / 10
    if (rounded < 60) return `${rounded.toFixed(1)} s`
    const total = Math.round(value)
    return `${Math.floor(total / 60)} min ${total % 60} s`
}

export type DurationUnit = 'ms' | 's'

/** One axis has one unit: milliseconds when every value is under a second, seconds otherwise. */
export function pickDurationUnit(values: (number | null)[]): DurationUnit {
    const largest = Math.max(0, ...values.filter((value): value is number => value !== null))
    return largest < 1 ? 'ms' : 's'
}

/** Axis tick for values in seconds, in the axis unit: 0.0075 -> "7.5 ms" (ms axis), 12.5 -> "12.5 s". */
export function formatDurationTick(seconds: number, unit: DurationUnit): string {
    return unit === 'ms' ? `${Number((seconds * 1000).toFixed(1))} ms` : `${Number(seconds.toFixed(1))} s`
}

/** The two tick formatters as ready-made functions, so the chart needs no inline callback. */
export function formatMillisTick(seconds: number): string {
    return formatDurationTick(seconds, 'ms')
}
export function formatSecondsTick(seconds: number): string {
    return formatDurationTick(seconds, 's')
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

/** Tooltip text for the trend chart: a duration for the solid line, a percentage for the dashed one. */
export function formatTrendTooltip(value: unknown, name: unknown): string {
    const number = typeof value === 'number' ? value : null
    return name === TREND_RATE_NAME ? formatPercent(number) : formatDuration(number)
}
