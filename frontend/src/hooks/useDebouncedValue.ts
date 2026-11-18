import { useEffect, useState } from 'react'

/**
 * The value, but only after it has stopped changing for `delayMs`. While someone is typing, every keystroke
 * restarts the timer, so a request is made once when they pause, not once per letter.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
    const [debounced, setDebounced] = useState(value)

    useEffect(() => {
        const timer = setTimeout(() => setDebounced(value), delayMs)
        return () => clearTimeout(timer) // a newer value (or leaving the page) cancels the pending one
    }, [value, delayMs])

    return debounced
}
