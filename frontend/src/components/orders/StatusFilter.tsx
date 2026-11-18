import { cn } from '@/utils/utils'
import { STATUS_FILTER_LABELS, STATUS_FILTERS, type StatusFilter as StatusValue } from '@/utils/orderSearch'

interface StatusFilterProps {
    value: StatusValue
    onChange: (status: StatusValue) => void
}

/**
 * One row of buttons: All, Completed, Pending, Processing, Failed. The chosen one is told apart three ways, so
 * colour is not the only cue: aria-pressed (screen readers), a check mark, and the filled background.
 */
export function StatusFilter({ value, onChange }: StatusFilterProps) {
    return (
        <div role="group" aria-label="Status" className="flex flex-wrap gap-2">
            {STATUS_FILTERS.map((status) => {
                const selected = status === value
                return (
                    <button
                        key={status}
                        type="button"
                        aria-pressed={selected}
                        onClick={() => onChange(status)}
                        className={cn(
                            'rounded-full border px-3 py-1 text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500',
                            selected
                                ? 'border-blue-600 bg-blue-600 text-white'
                                : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50',
                        )}
                    >
                        {selected && <span aria-hidden="true">✓ </span>}
                        {STATUS_FILTER_LABELS[status]}
                    </button>
                )
            })}
        </div>
    )
}
