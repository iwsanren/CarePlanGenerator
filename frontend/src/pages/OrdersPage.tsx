import { useAuth } from '@/auth/auth'
import { downloadFile } from '@/services/download'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Download } from 'lucide-react'

import { useOrders } from '@/hooks/useOrders'
import { useOrderSearch } from '@/hooks/useOrderSearch'
import { Button } from '@/components/ui/Button'
import { OrderFilterPanel } from '@/components/orders/OrderFilterPanel'
import { OrdersEmptyState, type EmptyKind } from '@/components/orders/OrdersEmptyState'
import { OrdersPagination } from '@/components/orders/OrdersPagination'
import { OrdersTable } from '@/components/orders/OrdersTable'
import { TableSkeleton } from '@/components/orders/TableSkeleton'
import { describeListError, hasActiveFilters } from '@/utils/orderSearch'

/** The query key used while the filters are invalid: nothing is sent for it, so only its shape matters. */
const NO_PARAMS = {}

export function OrdersPage() {
    const auth = useAuth()
    const [exportError, setExportError] = useState<string>()
    const exportCsv = async () => {
        try { await downloadFile('/export', 'orders.csv') }
        catch (error) { setExportError((error as Error).message || 'Export failed. Please try again.') }
    }

    // useNavigate gives us a function for navigating in code,
    // equivalent to the user clicking a <Link>.
    const navigate = useNavigate()

    // Filters, sort and page. `build` is either the request parameters or the fields that are wrong.
    const orderSearch = useOrderSearch()
    const { search, build } = orderSearch

    // Invalid filters send nothing: the last good result stays on screen and the fields explain what to fix.
    const { data, isLoading, isError, error, isPlaceholderData } = useOrders(
        build.ok ? build.params : NO_PARAMS,
        build.ok,
    )

    // Values derived from data. Since data may be undefined, use ?? as a fallback.
    const orders = data?.results ?? []
    const totalPages = data ? Math.max(1, Math.ceil(data.count / data.pageSize)) : 1
    const filtered = hasActiveFilters(search.filters)

    let emptyKind: EmptyKind = 'none'
    if (data && data.count > 0) emptyKind = 'empty-page'
    else if (filtered) emptyKind = 'no-match'

    return (
        <div>
            {/* Header: title + action buttons, shown in every state */}
            <div className="mb-6 flex items-center justify-between">
                <h1 className="text-2xl font-bold">Orders</h1>
                <div className="flex items-center gap-3">
                    {auth.pharmacist && filtered && (
                        <p className="text-xs text-gray-500">Export includes all orders, not only the filtered ones.</p>
                    )}
                    {auth.pharmacist && <Button
                        variant="outline"
                        onClick={() => { void exportCsv() }}
                    >
                        <Download className="mr-2 h-4 w-4" />
                        Export CSV
                    </Button>}
                    <Button onClick={() => navigate('/')}>New Order</Button>
                </div>
            </div>

            <OrderFilterPanel
                filters={orderSearch.liveFilters}
                errors={orderSearch.errors}
                onTextChange={orderSearch.setDraft}
                onStatusChange={orderSearch.setStatus}
                onDateChange={orderSearch.setDate}
                onClear={orderSearch.clear}
            />

            {exportError && <p role="alert" className="text-red-600">{exportError}</p>}
            {!build.ok && (
                <p className="mb-3 text-sm text-amber-700">Fix the highlighted filters to update the results.</p>
            )}

            {/* Four states: read this from top to bottom as if / else-if / else */}
            {isLoading ? (
                // 1. First load, with no data available yet
                <TableSkeleton />
            ) : isError ? (
                // 2. Request failed
                <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-4">
                    <p className="text-red-700">{describeListError(error)}</p>
                </div>
            ) : !data ? null : orders.length === 0 ? (
                // 3. Request succeeded, but there are no records (or none on this page)
                <OrdersEmptyState
                    kind={emptyKind}
                    onClearFilters={orderSearch.clear}
                    onFirstPage={() => orderSearch.setPage(1)}
                />
            ) : (
                // 4. Normal state: data is available
                <>
                    <OrdersTable
                        orders={orders}
                        sort={search.sort}
                        onSort={orderSearch.toggleSort}
                        dimmed={isPlaceholderData}
                    />
                    <OrdersPagination
                        page={search.page}
                        totalPages={totalPages}
                        count={data.count}
                        loading={isPlaceholderData}
                        onPageChange={orderSearch.setPage}
                    />
                </>
            )}
        </div>
    )
}
