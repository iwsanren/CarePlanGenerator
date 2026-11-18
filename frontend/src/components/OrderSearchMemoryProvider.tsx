import { useMemo, useState } from 'react'
import {
    EMPTY_MEMORY,
    OrderSearchMemoryContext,
    type OrderSearchMemory,
    type OrderSearchMemoryApi,
} from '@/hooks/useOrderSearchMemory'

/**
 * Holds the patient name and MRN of the order list search for as long as the provider is mounted. The Layout
 * renders it inside the sign-in gate, so signing out (the gate stops rendering its children) throws them away,
 * and so does a page reload. Nothing is written to localStorage or sessionStorage.
 */
export function OrderSearchMemoryProvider({ children }: { children: React.ReactNode }) {
    const [memory, setMemory] = useState<OrderSearchMemory>(EMPTY_MEMORY)

    const api = useMemo<OrderSearchMemoryApi>(
        () => ({ memory, setMemory, clearMemory: () => setMemory(EMPTY_MEMORY) }),
        [memory],
    )

    return <OrderSearchMemoryContext.Provider value={api}>{children}</OrderSearchMemoryContext.Provider>
}
