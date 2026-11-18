import { FileText } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/Button'

/**
 * Why the list is empty decides what to offer:
 * - none: there are no orders at all -> create the first one;
 * - no-match: orders exist, but the filters exclude them all -> clear the filters;
 * - empty-page: orders match, but this page is past the last one (an old link, or the list shrank) -> page 1.
 */
export type EmptyKind = 'none' | 'no-match' | 'empty-page'

interface OrdersEmptyStateProps {
    kind: EmptyKind
    onClearFilters: () => void
    onFirstPage: () => void
}

export function OrdersEmptyState({ kind, onClearFilters, onFirstPage }: OrdersEmptyStateProps) {
    return (
        <div className="rounded-lg bg-white p-8 text-center shadow">
            <FileText className="mx-auto mb-4 h-12 w-12 text-gray-400" aria-hidden="true" />
            {kind === 'none' && (
                <>
                    <p className="mb-4 text-gray-500">No orders yet</p>
                    <Link to="/" className="text-blue-600 hover:underline">
                        Create your first order
                    </Link>
                </>
            )}
            {kind === 'no-match' && (
                <>
                    <p className="mb-4 text-gray-500">No orders match your filters</p>
                    <Button variant="outline" onClick={onClearFilters}>Clear filters</Button>
                </>
            )}
            {kind === 'empty-page' && (
                <>
                    <p className="mb-4 text-gray-500">There are no results on this page.</p>
                    <Button variant="outline" onClick={onFirstPage}>Go to the first page</Button>
                </>
            )}
        </div>
    )
}
