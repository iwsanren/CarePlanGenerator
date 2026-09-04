import { Link, useParams } from 'react-router-dom'

import { useOrder, isNotFoundError } from '@/hooks/useOrders'
import { cn, getStatusColor } from '@/utils/utils'
import type { OrderResponse } from '@/types'

/* ------------------------------------------------------------------ */
/* Small building blocks                                               */
/* ------------------------------------------------------------------ */

/** A white rounded panel used for each section of the page. */
function Card({ title, children }: { title: string; children: React.ReactNode }) {
    return (
        <section className="rounded-lg bg-white p-6 shadow">
            <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-gray-500">
                {title}
            </h2>
            {children}
        </section>
    )
}

/** One "label: value" row inside a card. */
function Field({ label, value }: { label: string; value: React.ReactNode }) {
    return (
        <div className="flex gap-3 py-1 text-sm">
            <span className="w-28 shrink-0 text-gray-500">{label}</span>
            <span className="text-gray-900">{value}</span>
        </div>
    )
}

/** Shown while the request is in flight — grey bars that mirror the real layout. */
function DetailSkeleton() {
    return (
        <div className="animate-pulse space-y-4">
            <div className="h-8 w-40 rounded bg-gray-200" />
            {[0, 1, 2].map((i) => (
                <div key={i} className="rounded-lg bg-white p-6 shadow">
                    <div className="mb-3 h-3 w-24 rounded bg-gray-200" />
                    <div className="mb-2 h-4 w-64 rounded bg-gray-200" />
                    <div className="h-4 w-48 rounded bg-gray-200" />
                </div>
            ))}
        </div>
    )
}

/** Shown when the id is not a number, or the backend says the order does not exist. */
function NotFoundCard() {
    return (
        <div className="rounded-lg bg-white p-8 text-center shadow">
            <p className="mb-4 text-gray-500">Order not found</p>
            <Link to="/orders" className="text-blue-600 hover:underline">
                ← Back to orders
            </Link>
        </div>
    )
}

/** The body of the "Care Plan" card — one branch per order status. */
function CarePlanSection({ order }: { order: OrderResponse }) {
    switch (order.status) {
        case 'pending':
            return (
                <p className="text-sm text-gray-500">
                    Queued — the care plan has not been generated yet.
                </p>
            )
        case 'processing':
            return (
                <p className="text-sm text-blue-600">
                    Generating… refresh the page to check. (Live polling comes in a later step.)
                </p>
            )
        case 'completed':
            return order.carePlanContent ? (
                <pre className="whitespace-pre-wrap break-words rounded bg-gray-50 p-4 text-sm font-mono">
                    {order.carePlanContent}
                </pre>
            ) : (
                <p className="text-sm text-gray-500">
                    Marked complete, but no content was returned.
                </p>
            )
        case 'failed':
            return (
                <p className="text-sm text-red-600">
                    {order.message ?? 'Care plan generation failed.'}
                </p>
            )
        default:
            // status was something outside the known union — render nothing rather than crash.
            return null
    }
}

/* ------------------------------------------------------------------ */
/* Page                                                                */
/* ------------------------------------------------------------------ */

export function OrderDetailPage() {
    // useParams() reads the ":id" segment from the URL. It is always a string
    // (or undefined), so we convert it to a number for the hook.
    const { id: idParam } = useParams()
    const id = Number(idParam)

    const { data, isLoading, isError, error } = useOrder(id)

    // 1) The route param wasn't a number at all (e.g. /orders/abc): nothing to fetch.
    if (!Number.isFinite(id)) return <NotFoundCard />

    // 2) Request in flight.
    if (isLoading) return <DetailSkeleton />

    // 3) Request failed.
    if (isError) {
        // A missing order is an expected outcome, not a real failure.
        if (isNotFoundError(error)) return <NotFoundCard />
        return (
            <div className="rounded-lg border border-red-200 bg-red-50 p-4">
                <p className="text-red-700">
                    Something went wrong loading this order: {error?.message}
                </p>
                <Link
                    to="/orders"
                    className="mt-2 inline-block text-sm text-blue-600 hover:underline"
                >
                    ← Back to orders
                </Link>
            </div>
        )
    }

    // 4) Success. (isLoading / isError are both false, but TS doesn't narrow
    //    react-query's `data` from that, so guard once more.)
    if (!data) return null
    const order = data

    // GET /orders/{id} actually returns `warnings: null` (the backend mapper never
    // sets it), so guard before calling array methods on it.
    const warnings = order.warnings ?? []

    return (
        <div className="space-y-4">
            {/* Header: back link + order number + status badge */}
            <div>
                <Link to="/orders" className="text-sm text-blue-600 hover:underline">
                    ← Back to orders
                </Link>
                <div className="mt-1 flex items-center gap-3">
                    <h1 className="text-2xl font-bold">Order #{order.id}</h1>
                    <span
                        className={cn(
                            'rounded-full px-2 py-1 text-xs font-medium',
                            getStatusColor(order.status),
                        )}
                    >
                        {order.status}
                    </span>
                </div>
            </div>

            {/* Card A: Order */}
            <Card title="Order">
                <Field label="Medication" value={order.medicationName} />
                <Field label="Result" value={order.resultType} />
                {order.message && <Field label="Message" value={order.message} />}
                {warnings.length > 0 && (
                    <div className="mt-2">
                        <p className="text-sm text-gray-500">Warnings</p>
                        <ul className="mt-1 list-disc pl-5">
                            {warnings.map((w, i) => (
                                <li key={i} className="text-sm text-amber-700">
                                    {w}
                                </li>
                            ))}
                        </ul>
                    </div>
                )}
            </Card>

            {/* Card B: Care Plan */}
            <Card title="Care Plan">
                <CarePlanSection order={order} />
            </Card>

            {/* Card C: References — placeholder until patient / provider endpoints are wired */}
            <Card title="References">
                {/*
                    TODO(later): render full Patient / Provider cards once GET /patients/{id}
                    and GET /providers/by-id/{id} are reconciled. This page currently only
                    consumes GET /orders/{id}, which exposes ids but not nested objects.
                */}
                <p className="text-sm text-gray-400">
                    Patient #{order.patientId} · Provider #{order.providerId}
                </p>
            </Card>
        </div>
    )
}