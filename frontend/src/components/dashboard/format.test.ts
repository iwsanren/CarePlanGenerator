import { describe, expect, it } from 'vitest'
import {
  formatDuration,
  formatDurationTick,
  formatMillisTick,
  formatPercent,
  formatPercentTick,
  formatSecondsTick,
  formatShortDate,
  formatTrendTooltip,
  NO_DATA,
  pickDurationUnit,
  TREND_RATE_NAME,
  TREND_SECONDS_NAME,
} from './format'

describe('formatPercent', () => {
  it.each([
    [0.1429, '14.3%'],
    [0.5, '50.0%'],
    [0.625, '62.5%'],
    [1, '100.0%'],
  ])('%j -> %s', (value, text) => {
    expect(formatPercent(value)).toBe(text)
  })

  it('a real zero is "0.0%", but no data (null) is a dash, never "0%"', () => {
    expect(formatPercent(0)).toBe('0.0%')
    expect(formatPercent(null)).toBe('—')
  })
})

describe('formatPercentTick', () => {
  it.each([[0, '0%'], [0.25, '25%'], [1, '100%']])('%j -> %s', (value, text) => {
    expect(formatPercentTick(value)).toBe(text)
  })
})

describe('formatDuration', () => {
  it.each([
    [0, '0 ms'],
    [0.0066, '7 ms'],
    [0.0668, '67 ms'],
    [0.5, '500 ms'],
    [0.9994, '999 ms'],
    [1, '1.0 s'],
    [1.75, '1.8 s'],
    [12.34, '12.3 s'],
    [59.9, '59.9 s'],
    [60, '1 min 0 s'],
    [125, '2 min 5 s'],
    [3600, '60 min 0 s'],
  ])('%j -> %s', (value, text) => {
    expect(formatDuration(value)).toBe(text)
  })

  it('0.9996 s rounds up to a second instead of showing "1000 ms"', () => {
    expect(formatDuration(0.9996)).toBe('1.0 s')
  })

  it('something faster than half a millisecond is "<1 ms", but a true zero is "0 ms"', () => {
    expect(formatDuration(0.0004)).toBe('<1 ms')
    expect(formatDuration(0)).toBe('0 ms')
  })

  it('59.96 rounds up to a minute instead of showing "60.0 s"', () => {
    expect(formatDuration(59.96)).toBe('1 min 0 s')
  })

  it('no timing data (null) says so; it is never "0 ms"', () => {
    expect(formatDuration(null)).toBe(NO_DATA)
    expect(NO_DATA).toBe('No data')
  })
})

describe('pickDurationUnit', () => {
  it('uses milliseconds when every value is under a second', () => {
    expect(pickDurationUnit([0.007, 0.0665, null])).toBe('ms')
  })

  it('uses seconds as soon as one value reaches a second', () => {
    expect(pickDurationUnit([0.007, 1, null])).toBe('s')
    expect(pickDurationUnit([12.5, 30])).toBe('s')
  })

  it('with no values at all it falls back to milliseconds (nothing is drawn anyway)', () => {
    expect(pickDurationUnit([])).toBe('ms')
    expect(pickDurationUnit([null, null])).toBe('ms')
  })
})

describe('axis ticks for durations given in seconds', () => {
  it.each([
    [0, 'ms', '0 ms'],
    [0.0075, 'ms', '7.5 ms'],
    [0.02, 'ms', '20 ms'],
    [0, 's', '0 s'],
    [12.5, 's', '12.5 s'],
    [30, 's', '30 s'],
  ] as const)('%j in %s -> %s', (seconds, unit, text) => {
    expect(formatDurationTick(seconds, unit)).toBe(text)
  })

  it('the ready-made tick functions use their own unit', () => {
    expect(formatMillisTick(0.0075)).toBe('7.5 ms')
    expect(formatSecondsTick(12.5)).toBe('12.5 s')
  })
})

describe('formatShortDate', () => {
  it('formats a UTC calendar day without shifting it to local time', () => {
    expect(formatShortDate('2026-11-08')).toBe('Nov 8')
    expect(formatShortDate('2026-01-01')).toBe('Jan 1')
    expect(formatShortDate('2026-12-31')).toBe('Dec 31')
  })
})

describe('formatTrendTooltip', () => {
  it('shows the dashed series as a percentage', () => {
    expect(formatTrendTooltip(0.5, TREND_RATE_NAME)).toBe('50.0%')
  })

  it('shows the solid series as a duration in the unit that fits the value', () => {
    expect(formatTrendTooltip(12.5, TREND_SECONDS_NAME)).toBe('12.5 s')
    expect(formatTrendTooltip(0.0123, TREND_SECONDS_NAME)).toBe('12 ms')
  })

  it('a missing value is "no data" for either series', () => {
    expect(formatTrendTooltip(undefined, TREND_SECONDS_NAME)).toBe('No data')
    expect(formatTrendTooltip(null, TREND_RATE_NAME)).toBe('—')
    expect(formatTrendTooltip('x', TREND_RATE_NAME)).toBe('—')
  })
})
