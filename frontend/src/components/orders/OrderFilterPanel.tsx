import { useState } from 'react'
import { Filter } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { StatusFilter } from '@/components/orders/StatusFilter'
import type { TextField } from '@/hooks/useOrderSearch'
import {
    activeFilterCount,
    EARLIEST_DATE,
    LATEST_DATE,
    type FilterErrors,
    type FilterField,
    type OrderFilters,
    type StatusFilter as StatusValue,
} from '@/utils/orderSearch'

interface OrderFilterPanelProps {
    /** What the boxes show: the texts as they are typed, status and dates as chosen. */
    filters: OrderFilters
    errors: FilterErrors
    onTextChange: (field: TextField, value: string) => void
    onStatusChange: (status: StatusValue) => void
    onDateChange: (field: 'createdFrom' | 'createdTo', value: string) => void
    onClear: () => void
}

const TEXT_INPUTS: { field: TextField; label: string; placeholder: string; numeric?: boolean }[] = [
    { field: 'patientName', label: 'Patient name', placeholder: 'e.g. Jane Doe' },
    { field: 'mrn', label: 'MRN', placeholder: '6 digits', numeric: true },
    { field: 'medication', label: 'Medication', placeholder: 'e.g. IVIG' },
    { field: 'providerName', label: 'Provider name', placeholder: 'e.g. Dr. Smith' },
]

const DATE_INPUTS: { field: 'createdFrom' | 'createdTo'; label: string }[] = [
    { field: 'createdFrom', label: 'Created from (UTC)' },
    { field: 'createdTo', label: 'Created to (UTC)' },
]

const DATE_HINT_ID = 'order-filter-date-hint'

/**
 * The filter panel above the table. Every box has a visible <label>; a message about a box is tied to it with
 * aria-describedby and announced when it appears. On a narrow screen the panel folds behind a "Filters" button
 * (aria-expanded); from the md breakpoint up it is always open.
 */
export function OrderFilterPanel({
    filters, errors, onTextChange, onStatusChange, onDateChange, onClear,
}: OrderFilterPanelProps) {
    const [open, setOpen] = useState(false)
    const count = activeFilterCount(filters)

    const messageId = (field: FilterField) => `order-filter-${field}-error`

    return (
        <section aria-label="Filters" className="mb-6 rounded-lg bg-white p-4 shadow">
            <div className="flex items-center justify-between md:hidden">
                <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    aria-expanded={open}
                    aria-controls="order-filter-panel"
                    onClick={() => setOpen((current) => !current)}
                >
                    <Filter className="mr-2 h-4 w-4" aria-hidden="true" />
                    Filters{count > 0 ? ` (${count})` : ''}
                </Button>
            </div>

            <div id="order-filter-panel" className={open ? 'mt-4 block md:mt-0' : 'hidden md:block'}>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
                    {TEXT_INPUTS.map(({ field, label, placeholder, numeric }) => (
                        <div key={field}>
                            <label htmlFor={`order-filter-${field}`} className="mb-1 block text-sm font-medium text-gray-700">
                                {label}
                            </label>
                            <Input
                                id={`order-filter-${field}`}
                                value={filters[field]}
                                placeholder={placeholder}
                                inputMode={numeric ? 'numeric' : undefined}
                                autoComplete="off"
                                error={Boolean(errors[field])}
                                aria-invalid={Boolean(errors[field])}
                                aria-describedby={errors[field] ? messageId(field) : undefined}
                                onChange={(event) => onTextChange(field, event.target.value)}
                            />
                            {errors[field] && (
                                <p id={messageId(field)} role="alert" className="mt-1 text-sm text-red-600">
                                    {errors[field]}
                                </p>
                            )}
                        </div>
                    ))}
                </div>

                <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
                    {DATE_INPUTS.map(({ field, label }) => (
                        <div key={field}>
                            <label htmlFor={`order-filter-${field}`} className="mb-1 block text-sm font-medium text-gray-700">
                                {label}
                            </label>
                            <Input
                                id={`order-filter-${field}`}
                                type="date"
                                min={EARLIEST_DATE}
                                max={LATEST_DATE}
                                value={filters[field]}
                                error={Boolean(errors[field])}
                                aria-invalid={Boolean(errors[field])}
                                aria-describedby={errors[field] ? `${messageId(field)} ${DATE_HINT_ID}` : DATE_HINT_ID}
                                onChange={(event) => onDateChange(field, event.target.value)}
                            />
                            {errors[field] && (
                                <p id={messageId(field)} role="alert" className="mt-1 text-sm text-red-600">
                                    {errors[field]}
                                </p>
                            )}
                        </div>
                    ))}
                    <p id={DATE_HINT_ID} className="self-end text-xs text-gray-500">
                        Whole UTC calendar days, both ends included. Near midnight your local date can differ by a day.
                    </p>
                </div>

                <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
                    <div>
                        <p className="mb-1 text-sm font-medium text-gray-700">Status</p>
                        <StatusFilter value={filters.status} onChange={onStatusChange} />
                    </div>
                    <Button type="button" variant="outline" size="sm" disabled={count === 0} onClick={onClear}>
                        Clear all filters
                    </Button>
                </div>
            </div>
        </section>
    )
}
