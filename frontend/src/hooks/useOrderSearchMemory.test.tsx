import { describe, expect, it } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { OrderSearchMemoryProvider } from '@/components/OrderSearchMemoryProvider'
import { EMPTY_MEMORY, useOrderSearchMemory } from './useOrderSearchMemory'

const JANE = { patientName: 'Jane Doe', mrn: '123456' }

describe('useOrderSearchMemory without a provider (a page rendered on its own)', () => {
  it('starts empty, remembers what is set, and forgets it on clearMemory', () => {
    const { result } = renderHook(() => useOrderSearchMemory())
    expect(result.current.memory).toEqual(EMPTY_MEMORY)

    act(() => result.current.setMemory(JANE))
    expect(result.current.memory).toEqual(JANE)

    act(() => result.current.clearMemory())
    expect(result.current.memory).toEqual(EMPTY_MEMORY)
  })

  it('is not shared: another component starts empty', () => {
    const first = renderHook(() => useOrderSearchMemory())
    act(() => first.result.current.setMemory(JANE))

    const second = renderHook(() => useOrderSearchMemory())

    expect(second.result.current.memory).toEqual(EMPTY_MEMORY)
  })
})

describe('OrderSearchMemoryProvider', () => {
  function wrapper({ children }: { children: ReactNode }) {
    return <OrderSearchMemoryProvider>{children}</OrderSearchMemoryProvider>
  }

  it('starts empty, remembers what is set, and forgets it on clearMemory', () => {
    const { result } = renderHook(() => useOrderSearchMemory(), { wrapper })
    expect(result.current.memory).toEqual(EMPTY_MEMORY)

    act(() => result.current.setMemory(JANE))
    expect(result.current.memory).toEqual(JANE)

    act(() => result.current.clearMemory())
    expect(result.current.memory).toEqual(EMPTY_MEMORY)
  })

  it('is shared by everything below it', () => {
    const { result } = renderHook(
      () => ({ one: useOrderSearchMemory(), two: useOrderSearchMemory() }),
      { wrapper },
    )

    act(() => result.current.one.setMemory(JANE))

    expect(result.current.two.memory).toEqual(JANE)
  })

  it('lives only as long as the provider: a new provider starts empty', () => {
    const first = renderHook(() => useOrderSearchMemory(), { wrapper })
    act(() => first.result.current.setMemory(JANE))
    first.unmount() // what the sign-in gate does to the Layout on sign-out

    const second = renderHook(() => useOrderSearchMemory(), { wrapper })

    expect(second.result.current.memory).toEqual(EMPTY_MEMORY)
  })

  it('writes nothing to the browser storage', () => {
    const { result } = renderHook(() => useOrderSearchMemory(), { wrapper })

    act(() => result.current.setMemory(JANE))

    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
  })
})
