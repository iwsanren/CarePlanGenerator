import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Download, FileText, Loader2 } from 'lucide-react'

import { useOrders } from '@/hooks/useOrders'
import { formatDateTime, getStatusColor, cn } from '@/utils/utils'
import { Button } from '@/components/ui/Button'
import type { OrderStatus } from '@/types'

// Number of rows per page. To see pagination controls right away,
const PAGE_SIZE = 20

const COLUMNS = ['Patient', 'Provider', 'Medication', 'Status', 'Created', 'Care Plan']

/**
 * The backend list endpoint does not include a hasCarePlan field,
 * but status already represents the care plan status
 * because the Java model stores the status on CarePlan.
 * So this column can be derived directly from status.
 */
function carePlanLabel(status: OrderStatus) {
    switch (status) {
        case 'completed':
            return <span className="text-green-600">✓ Ready</span>
        case 'processing':
            return <span className="text-blue-600">Generating…</span>
        case 'failed':
            return <span className="text-red-600">Failed</span>
        default:
            return <span className="text-gray-400">Pending</span>
    }
}

/** Loading skeleton: gray bars with the same structure as the real table, plus a pulse animation */
function TableSkeleton() {
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

export function OrdersPage() {
    // page is local component state. setPage(n) tells React to re-render this component with the new value.
    const [page, setPage] = useState(1)

    // useNavigate gives us a function for navigating in code,
    // equivalent to the user clicking a <Link>.
    const navigate = useNavigate()

    const { data, isLoading, isError, error, isPlaceholderData } = useOrders({
        page,
        page_size: PAGE_SIZE,
    })

    // Values derived from data. Since data may be undefined, use ?? as a fallback.
    const orders = data?.results ?? []
    const totalPages = data ? Math.max(1, Math.ceil(data.count / data.pageSize)) : 1

    return (
        <div>
            {/* Header: title + action buttons, shown in every state */}
            <div className="mb-6 flex items-center justify-between">
                <h1 className="text-2xl font-bold">Orders</h1>
                <div className="flex gap-3">
                    <Button
                        variant="outline"
                        onClick={() =>
                            window.open('/api/v1/reports/orders/export', '_blank')
                        }
                    >
                        <Download className="mr-2 h-4 w-4" />
                        Export CSV
                    </Button>
                    <Button onClick={() => navigate('/')}>New Order</Button>
                </div>
            </div>

            {/* Four states: read this from top to bottom as if / else-if / else */}
            {isLoading ? (
                // 1. First load, with no data available yet
                <TableSkeleton />
            ) : isError ? (
                // 2. Request failed
                <div className="rounded-lg border border-red-200 bg-red-50 p-4">
                    <p className="text-red-700">
                        Error loading orders: {error?.message}
                    </p>
                </div>
            ) : orders.length === 0 ? (
                // 3. Request succeeded, but there are no records
                <div className="rounded-lg bg-white p-8 text-center shadow">
                    <FileText className="mx-auto mb-4 h-12 w-12 text-gray-400" />
                    <p className="mb-4 text-gray-500">No orders yet</p>
                    <Link to="/" className="text-blue-600 hover:underline">
                        Create your first order
                    </Link>
                </div>
            ) : (
                // 4. Normal state: data is available
                <>
                    <div
                        className={cn(
                            'overflow-hidden rounded-lg bg-white shadow transition-opacity',
                            isPlaceholderData && 'opacity-50', // Loading another page: make the table semi-transparent
                        )}
                    >
                        <table className="min-w-full divide-y divide-gray-200">
                            <thead className="bg-gray-50">
                            <tr>
                                {COLUMNS.map((h) => (
                                    <th
                                        key={h}
                                        className="px-6 py-3 text-left text-xs font-medium uppercase text-gray-500"
                                    >
                                        {h}
                                    </th>
                                ))}
                            </tr>
                            </thead>
                            <tbody className="divide-y divide-gray-200 bg-white">
                            {orders.map((order) => (
                                <tr
                                    key={order.id}
                                    onClick={() => navigate(`/orders/${order.id}`)}
                                    className="cursor-pointer hover:bg-gray-50"
                                >
                                    <td className="px-6 py-4">
                                        <Link
                                            to={`/orders/${order.id}`}
                                            onClick={(e) => e.stopPropagation()}
                                            className="text-blue-600 hover:underline"
                                        >
                                            {order.patientName}
                                        </Link>
                                        <p className="text-sm text-gray-500">MRN: —</p>
                                    </td>
                                    <td className="px-6 py-4 text-gray-400">—</td>
                                    <td className="px-6 py-4">{order.medicationName}</td>
                                    <td className="px-6 py-4">
                      <span
                          className={cn(
                              'rounded-full px-2 py-1 text-xs font-medium',
                              getStatusColor(order.status),
                          )}
                      >
                        {order.status}
                      </span>
                                    </td>
                                    <td className="px-6 py-4 text-sm text-gray-500">
                                        {formatDateTime(order.createdAt)}
                                    </td>
                                    <td className="px-6 py-4">{carePlanLabel(order.status)}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>

                    {/* Pagination controls: do not render this block when there is only one page */}
                    {totalPages > 1 && (
                        <div className="mt-4 flex items-center justify-between">
                            <p className="text-sm text-gray-500">
                                Page {page} of {totalPages} · {data?.count} orders
                            </p>
                            <div className="flex items-center gap-2">
                                <Button
                                    variant="outline"
                                    size="sm"
                                    disabled={page <= 1}
                                    onClick={() => setPage((p) => p - 1)}
                                >
                                    Previous
                                </Button>
                                <Button
                                    variant="outline"
                                    size="sm"
                                    disabled={page >= totalPages}
                                    onClick={() => setPage((p) => p + 1)}
                                >
                                    Next
                                </Button>
                                {isPlaceholderData && (
                                    <Loader2 className="h-4 w-4 animate-spin text-gray-400" />
                                )}
                            </div>
                        </div>
                    )}
                </>
            )}
        </div>
    )
}