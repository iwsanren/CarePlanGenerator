import { useOrders } from '@/hooks/useOrders.ts'

/**
 * Temporary version for 1c Step 1: only verifies that data can flow from the backend to the page.
 * Step 2 will replace this return block with the real table plus loading / empty / error states.
 */
export function OrdersPage() {
    // Call the hook and read the three main request states
    const { data, isLoading, error } = useOrders({ page: 1, page_size: 20 })

    // Conditional rendering: return different JSX for different states.
    // Returning early means the rest of the function will not continue.
    if (isLoading) return <p>Loading…</p>
    if (error) return <p className="text-red-600">Error: {error.message}</p>

    return (
        <div>
            <h2 className="mb-4 text-lg font-semibold">
                Orders ({data?.count ?? 0} total)
            </h2>

            {/* data?.results.map(...): returns one <li> for each array item, which React renders as a list */}
            <ul className="list-disc pl-6">
                {data?.results.map((o) => (
                    <li key={o.id}>
                        #{o.id} · {o.patientName} · {o.medicationName} · {o.status} ·{' '}
                        {o.createdAt}
                    </li>
                ))}
            </ul>

            {/* Also print the raw JSON so the field names and structure can be checked by eye */}
            <pre className="mt-6 overflow-x-auto rounded bg-gray-100 p-4 text-xs">
        {JSON.stringify(data, null, 2)}
      </pre>
        </div>
    )
}