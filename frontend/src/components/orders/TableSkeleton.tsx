/** Loading skeleton: gray bars with the same structure as the real table, plus a pulse animation */
export function TableSkeleton() {
    return (
        <div className="overflow-hidden rounded-lg bg-white shadow">
            <div className="animate-pulse divide-y divide-gray-200">
                {Array.from({ length: 6 }).map((_, i) => (
                    <div key={i} className="flex gap-6 px-6 py-4">
                        <div className="h-4 w-32 rounded bg-gray-200" />
                        <div className="h-4 w-32 rounded bg-gray-200" />
                        <div className="h-4 w-40 rounded bg-gray-200" />
                        <div className="h-4 w-20 rounded bg-gray-200" />
                        <div className="h-4 w-36 rounded bg-gray-200" />
                        <div className="h-4 w-20 rounded bg-gray-200" />
                    </div>
                ))}
            </div>
        </div>
    )
}
