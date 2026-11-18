import { Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { resultsLabel } from '@/utils/orderSearch'

interface OrdersPaginationProps {
    page: number
    totalPages: number
    /** Total number of orders that match, over all pages. */
    count: number
    /** True while another page or search is loading. */
    loading: boolean
    onPageChange: (page: number) => void
}

/**
 * Under the table: how many orders match (a polite live region, so a screen reader hears the new total after a
 * search), and, when there is more than one page, the page position with Previous and Next.
 */
export function OrdersPagination({ page, totalPages, count, loading, onPageChange }: OrdersPaginationProps) {
    return (
        <div className="mt-4 flex items-center justify-between">
            <div>
                <p role="status" className="text-sm font-medium text-gray-700">{resultsLabel(count)}</p>
                {totalPages > 1 && (
                    <p className="text-sm text-gray-500">
                        Page {page} of {totalPages} · {count} orders
                    </p>
                )}
            </div>
            {totalPages > 1 && (
                <div className="flex items-center gap-2">
                    <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>
                        Previous
                    </Button>
                    <Button
                        variant="outline"
                        size="sm"
                        disabled={page >= totalPages}
                        onClick={() => onPageChange(page + 1)}
                    >
                        Next
                    </Button>
                    {loading && <Loader2 className="h-4 w-4 animate-spin text-gray-400" aria-hidden="true" />}
                </div>
            )}
        </div>
    )
}
