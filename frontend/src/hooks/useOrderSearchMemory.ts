import { createContext, useContext, useState } from 'react'

/**
 * The two patient fields of the order list search. They are patient information, so they are kept out of the URL
 * (address bar, history, server logs) and live only in memory: they survive going to an order and back, and are
 * gone after a reload, a sign-out, or a click on the "Orders" link in the navigation.
 */
export interface OrderSearchMemory {
    patientName: string
    mrn: string
}

export const EMPTY_MEMORY: OrderSearchMemory = { patientName: '', mrn: '' }

export interface OrderSearchMemoryApi {
    memory: OrderSearchMemory
    setMemory: (next: OrderSearchMemory) => void
    clearMemory: () => void
}

/** Provided by OrderSearchMemoryProvider (in the Layout, which is inside the sign-in gate). */
export const OrderSearchMemoryContext = createContext<OrderSearchMemoryApi | null>(null)

/**
 * The memory from the provider. Without a provider (a page rendered on its own, as in unit tests) the same API
 * is backed by local state, so the search still works; it just does not outlive the component.
 */
export function useOrderSearchMemory(): OrderSearchMemoryApi {
    const shared = useContext(OrderSearchMemoryContext)
    const [local, setLocal] = useState(EMPTY_MEMORY)
    return shared ?? { memory: local, setMemory: setLocal, clearMemory: () => setLocal(EMPTY_MEMORY) }
}
