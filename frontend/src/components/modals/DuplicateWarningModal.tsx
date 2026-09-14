import { AlertTriangle, XCircle } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { cn } from '@/utils/utils'
import type { Warning } from '@/types'

interface DuplicateWarningModalProps {
    open: boolean
    /** Present -> render the blocking variant (Close only). Absent -> confirmable variant. */
    blockingMessage?: string | null
    warnings?: Warning[] | null
    onClose: () => void
    onConfirm?: () => void
}

export function DuplicateWarningModal({
    open,
    blockingMessage,
    warnings,
    onClose,
    onConfirm,
}: DuplicateWarningModalProps) {
    // A component can render nothing at all — returning null is valid JSX
    // (see glossary: JSX). This is how "modal closed" is expressed: we don't
    // toggle a CSS class, we just don't render the element this time.
    if (!open) return null

    const isBlocking = Boolean(blockingMessage)

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
            <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
                <div className="mb-4 flex items-center gap-3">
                    {isBlocking ? (
                        <XCircle className="h-6 w-6 shrink-0 text-red-500" />
                    ) : (
                        <AlertTriangle className="h-6 w-6 shrink-0 text-amber-500" />
                    )}
                    <h2 className="text-lg font-semibold">
                        {isBlocking ? 'This order cannot be created' : 'Possible duplicate detected'}
                    </h2>
                </div>

                {isBlocking ? (
                    <p className="mb-6 text-sm text-gray-700">{blockingMessage}</p>
                ) : (
                    <ul className="mb-6 space-y-2">
                        {(warnings ?? []).map((w) => (
                            <li
                                key={w.code}
                                className={cn(
                                    'rounded-md px-3 py-2 text-sm',
                                    w.actionRequired
                                        ? 'bg-amber-50 text-amber-800'
                                        : 'bg-blue-50 text-blue-800',
                                )}
                            >
                                {w.message}
                            </li>
                        ))}
                    </ul>
                )}

                <div className="flex justify-end gap-3">
                    <Button variant="outline" onClick={onClose}>
                        {isBlocking ? 'Close' : 'Cancel'}
                    </Button>
                    {!isBlocking && (
                        <Button variant="destructive" onClick={onConfirm}>
                            Continue Anyway
                        </Button>
                    )}
                </div>
            </div>
        </div>
    )
}
