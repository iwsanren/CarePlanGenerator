import { describe, expect, it } from 'vitest'
import {
  formatPercent,
  formatPercentTick,
  formatSeconds,
  formatShortDate,
  formatTrendTooltip,
  NO_DATA,
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

describe('formatSeconds', () => {
  it.each([
    [0, '0.0 s'],
    [1.75, '1.8 s'],
    [12.34, '12.3 s'],
    [59.9, '59.9 s'],
    [60, '1 min 0 s'],
    [125, '2 min 5 s'],
    [3600, '60 min 0 s'],
  ])('%j -> %s', (value, text) => {
    expect(formatSeconds(value)).toBe(text)
  })

  it('59.96 rounds up to a minute instead of showing "60.0 s"', () => {
    expect(formatSeconds(59.96)).toBe('1 min 0 s')
  })

  it('no timing data (null) says so; it is never "0 s"', () => {
    expect(formatSeconds(null)).toBe(NO_DATA)
    expect(NO_DATA).toBe('No data')
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

  it('shows the solid series in seconds', () => {
    expect(formatTrendTooltip(12.5, TREND_SECONDS_NAME)).toBe('12.5 s')
  })

  it('a missing value is "no data" for either series', () => {
    expect(formatTrendTooltip(undefined, TREND_SECONDS_NAME)).toBe('No data')
    expect(formatTrendTooltip(null, TREND_RATE_NAME)).toBe('—')
    expect(formatTrendTooltip('x', TREND_RATE_NAME)).toBe('—')
  })
})
