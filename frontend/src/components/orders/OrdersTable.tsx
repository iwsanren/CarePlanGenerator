import { Link, useLocation, useNavigate } from 'react-router-dom'
import { SortableHeader } from '@/components/orders/SortableHeader'
import type { OrderListItem, OrderStatus } from '@/types'
import { describeSort, type OrderSort, type SortColumn } from '@/utils/orderSearch'
import type { OrderLinkState } from '@/utils/orderSearchUrl'
import { cn, formatDateTime, getStatusColor } from '@/utils/utils'

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

interface OrdersTableProps {
    orders: OrderListItem[]
    sort: OrderSort | null
    onSort: (column: SortColumn) => void
    /** True while the rows on screen belong to the previous search (a new one is loading). */
    dimmed: boolean
}

/** The orders, one row each. Five headers sort the list; "Care Plan" does not (it is derived from the status). */
export function OrdersTable({ orders, sort, onSort, dimmed }: OrdersTableProps) {
    const navigate = useNavigate()
    // The order remembers which list it was opened from, so its "Back to orders" link can return to it.
    const { search } = useLocation()
    const linkState: OrderLinkState = { ordersSearch: search }

    return (
        <>
            <p className="mb-2 text-sm text-gray-500">{describeSort(sort)}</p>
            {/* overflow-x-auto: on a narrow screen the table scrolls sideways instead of being cut off */}
            <div
                className={cn(
                    'overflow-x-auto rounded-lg bg-white shadow transition-opacity',
                    dimmed && 'opacity-50', // loading another page or search: make the table semi-transparent
                )}
            >
                <table className="min-w-full divide-y divide-gray-200">
                    <thead className="bg-gray-50">
                    <tr>
                        <SortableHeader label="Patient" column="patient_name" sort={sort} onSort={onSort} />
                        <SortableHeader label="Provider" column="provider_name" sort={sort} onSort={onSort} />
                        <SortableHeader label="Medication" column="medication" sort={sort} onSort={onSort} />
                        <SortableHeader label="Status" column="status" sort={sort} onSort={onSort} />
                        <SortableHeader label="Created" column="created_at" sort={sort} onSort={onSort} />
                        <th scope="col" className="px-6 py-3 text-left text-xs font-medium uppercase text-gray-500">
                            Care Plan
                        </th>
                    </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-200 bg-white">
                    {orders.map((order) => (
                        <tr
                            key={order.id}
                            onClick={() => navigate(`/orders/${order.id}`, { state: linkState })}
                            className="cursor-pointer hover:bg-gray-50"
                        >
                            <td className="px-6 py-4">
                                <Link
                                    to={`/orders/${order.id}`}
                                    state={linkState}
                                    onClick={(e) => e.stopPropagation()}
                                    className="text-blue-600 hover:underline"
                                >
                                    {order.patientName}
                                </Link>
                                <p className="text-sm text-gray-500">MRN: {order.patientMrn}</p>
                            </td>
                            <td className="px-6 py-4 text-gray-600">{order.providerName}</td>
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
        </>
    )
}
