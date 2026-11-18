import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useDebouncedValue } from './useDebouncedValue'

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useDebouncedValue', () => {
  it('starts with the value it is given', () => {
    const { result } = renderHook(() => useDebouncedValue('a', 300))

    expect(result.current).toBe('a')
  })

  it('keeps the old value until the delay has passed, then switches', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: 'a' },
    })

    rerender({ value: 'b' })
    act(() => { vi.advanceTimersByTime(299) })
    expect(result.current).toBe('a')

    act(() => { vi.advanceTimersByTime(1) })
    expect(result.current).toBe('b')
  })

  it('restarts the wait on every change, so only the last value of a burst arrives', () => {
    const seen: string[] = []
    const { result, rerender } = renderHook(({ value }) => {
      const debounced = useDebouncedValue(value, 300)
      seen.push(debounced)
      return debounced
    }, { initialProps: { value: 'a' } })

    for (const value of ['ab', 'abc', 'abcd']) {
      rerender({ value })
      act(() => { vi.advanceTimersByTime(200) }) // always less than the delay
    }
    expect(result.current).toBe('a') // 600 ms have passed in total, but the typing never paused for 300

    act(() => { vi.advanceTimersByTime(100) })
    expect(result.current).toBe('abcd')
    expect(seen).not.toContain('ab') // the in-between values never came out
    expect(seen).not.toContain('abc')
  })

  it('works with objects: a new object arrives after the delay', () => {
    const first = { text: '' }
    const second = { text: 'x' }
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: first },
    })

    rerender({ value: second })
    expect(result.current).toBe(first)
    act(() => { vi.advanceTimersByTime(300) })
    expect(result.current).toBe(second)
  })

  it('cancels the pending change when the component goes away', () => {
    const { rerender, unmount } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: 'a' },
    })
    rerender({ value: 'b' })
    expect(vi.getTimerCount()).toBe(1)

    unmount()

    expect(vi.getTimerCount()).toBe(0)
  })
})
