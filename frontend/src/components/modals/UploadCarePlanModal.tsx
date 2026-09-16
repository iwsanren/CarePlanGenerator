import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Textarea } from '@/components/ui/Textarea'
import { cn } from '@/utils/utils'

interface UploadCarePlanModalProps {
    open: boolean
    submitting?: boolean
    onClose: () => void
    onSubmit: (upload: { text?: string; file?: File }) => void
}

type Mode = 'text' | 'file'
const PREVIEW_LIMIT = 500

export function UploadCarePlanModal({ open, submitting, onClose, onSubmit }: UploadCarePlanModalProps) {
    const [mode, setMode] = useState<Mode>('text')
    const [text, setText] = useState('')
    const [file, setFile] = useState<File | null>(null)
    const [filePreview, setFilePreview] = useState('')

    if (!open) return null

    function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
        const selected = e.target.files?.[0] ?? null
        setFile(selected)
        setFilePreview('')
        if (!selected) return

        // FileReader reads the file's bytes in the browser, asynchronously; onload
        // fires once done. This is ONLY for the local preview — the File object
        // itself (not this decoded text) is what gets sent to the backend.
        const reader = new FileReader()
        reader.onload = () => {
            const decoded = String(reader.result ?? '')
            setFilePreview(decoded.length > PREVIEW_LIMIT ? decoded.slice(0, PREVIEW_LIMIT) + '...' : decoded)
        }
        reader.readAsText(selected)
    }

    function handleSubmit() {
        if (mode === 'text') {
            onSubmit({ text })
        } else if (file) {
            onSubmit({ file })
        }
    }

    const canSubmit = mode === 'text' ? text.trim().length > 0 : Boolean(file)

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
            <div className="w-full max-w-lg rounded-lg bg-white p-6 shadow-xl">
                <h2 className="mb-4 text-lg font-semibold">Upload Care Plan</h2>

                <div className="mb-4 flex gap-2 border-b">
                    <button
                        type="button"
                        onClick={() => setMode('text')}
                        className={cn(
                            'px-3 py-2 text-sm font-medium',
                            mode === 'text' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-500',
                        )}
                    >
                        Paste Text
                    </button>
                    <button
                        type="button"
                        onClick={() => setMode('file')}
                        className={cn(
                            'px-3 py-2 text-sm font-medium',
                            mode === 'file' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-500',
                        )}
                    >
                        Upload File
                    </button>
                </div>

                {mode === 'text' ? (
                    <Textarea
                        rows={10}
                        value={text}
                        onChange={(e) => setText(e.target.value)}
                        placeholder="Paste the full care plan content here"
                    />
                ) : (
                    <div className="space-y-3">
                        <input type="file" accept=".txt" onChange={handleFileChange} />
                        {filePreview && (
                            <pre className="max-h-40 overflow-y-auto whitespace-pre-wrap rounded bg-gray-50 p-3 text-xs text-gray-600">
                                {filePreview}
                            </pre>
                        )}
                    </div>
                )}

                <div className="mt-6 flex justify-end gap-3">
                    <Button variant="outline" onClick={onClose} disabled={submitting}>
                        Cancel
                    </Button>
                    <Button onClick={handleSubmit} disabled={!canSubmit || submitting}>
                        {submitting ? 'Uploading…' : 'Upload'}
                    </Button>
                </div>
            </div>
        </div>
    )
}
