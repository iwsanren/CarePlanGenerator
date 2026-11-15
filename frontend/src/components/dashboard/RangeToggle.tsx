import type { DashboardDays } from '@/types'
import { cn } from '@/utils/utils'

const OPTIONS: { days: DashboardDays; label: string }[] = [
    { days: 7, label: 'Last 7 days' },
    { days: 30, label: 'Last 30 days' },
]

/** Two toggle buttons; aria-pressed tells a screen reader which one is on. */
export function RangeToggle({ value, onChange }: { value: DashboardDays; onChange: (days: DashboardDays) => void }) {
    return (
        <div role="group" aria-label="Time range" className="inline-flex rounded-lg border border-gray-300 bg-white p-1">
            {OPTIONS.map((option) => (
                <button
                    key={option.days}
                    type="button"
                    aria-pressed={value === option.days}
                    onClick={() => onChange(option.days)}
                    className={cn(
                        'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
                        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-600',
                        value === option.days ? 'bg-teal-700 text-white' : 'text-gray-600 hover:bg-gray-100',
                    )}
                >
                    {option.label}
                </button>
            ))}
        </div>
    )
}
