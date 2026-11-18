import type { OrderSort, SortColumn } from '@/utils/orderSearch'

interface SortableHeaderProps {
    label: string
    column: SortColumn
    /** The sort that is in force (null = the default order). */
    sort: OrderSort | null
    onSort: (column: SortColumn) => void
}

/**
 * A column header that sorts the list. Click: ascending, then descending, then back to the default order.
 * The state is in aria-sort on the <th> (what a screen reader reads) and in the arrow (what a sighted user
 * sees); the arrow is decoration only, so the column's name stays "Patient", not "Patient ▲".
 */
export function SortableHeader({ label, column, sort, onSort }: SortableHeaderProps) {
    const direction = sort?.column === column ? sort.direction : null
    const ariaSort = direction === 'asc' ? 'ascending' : direction === 'desc' ? 'descending' : 'none'
    const arrow = direction === 'asc' ? '▲' : direction === 'desc' ? '▼' : '↕'

    return (
        <th
            scope="col"
            aria-sort={ariaSort}
            className="px-6 py-3 text-left text-xs font-medium uppercase text-gray-500"
        >
            <button
                type="button"
                onClick={() => onSort(column)}
                className="inline-flex items-center gap-1 rounded uppercase hover:text-gray-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            >
                {label}
                <span aria-hidden="true" className={direction === null ? 'text-gray-300' : 'text-gray-900'}>
                    {arrow}
                </span>
            </button>
        </th>
    )
}
