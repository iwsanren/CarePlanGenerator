import { useId, type ReactNode } from 'react'
import { cn } from '@/utils/utils'

export interface ChartTable {
    headers: string[]
    rows: (string | number)[][]
}

interface ChartCardProps {
    title: string
    subtitle: string
    /** One-sentence description of the chart; becomes the aria-label of the graphic. */
    summary: string
    /** The same data as text. Rendered visually hidden, as a SIBLING of the graphic (see below). */
    table?: ChartTable
    /** Visible text that belongs to the chart (e.g. a legend with numbers). Also a sibling of the graphic. */
    legend?: ReactNode
    /** When set, the card shows this message instead of the chart: "no data" is said, not drawn. */
    noData?: string
    className?: string
    children: ReactNode
}

/**
 * A titled card around one chart.
 *
 * Why the table and the legend are NOT inside the role="img" element: role="img" makes everything
 * inside it presentational for a screen reader (it reads the aria-label and skips the children).
 * A data table or legend inside it would be unreachable, so they sit next to it.
 */
export function ChartCard({ title, subtitle, summary, table, legend, noData, className, children }: ChartCardProps) {
    const headingId = useId()
    return (
        <section aria-labelledby={headingId} className={cn('rounded-lg bg-white p-4 shadow', className)}>
            <h2 id={headingId} className="text-base font-semibold text-gray-900">{title}</h2>
            <p className="mb-3 text-xs text-gray-500">{subtitle}</p>
            {noData ? (
                <p className="py-10 text-center text-sm text-gray-500">{noData}</p>
            ) : (
                <>
                    <div role="img" aria-label={summary}>{children}</div>
                    {legend}
                    {table && (
                        <table className="sr-only">
                            <caption>{title}</caption>
                            <thead>
                                <tr>{table.headers.map((header) => <th key={header} scope="col">{header}</th>)}</tr>
                            </thead>
                            <tbody>
                                {table.rows.map((row) => (
                                    <tr key={row[0]}>
                                        {row.map((cell, index) => <td key={index}>{cell}</td>)}
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </>
            )}
        </section>
    )
}
