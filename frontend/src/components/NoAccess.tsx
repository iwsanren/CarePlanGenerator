/**
 * Shown instead of the dashboard to anybody who is not a pharmacist.
 * This is a courtesy for the user, not a security control: the backend answers 403 on its own.
 */
export function NoAccess() {
    return (
        <div className="rounded-lg border border-amber-200 bg-amber-50 p-6">
            <h1 className="mb-2 text-xl font-bold text-amber-900">No permission</h1>
            <p className="text-amber-900">The dashboard is available to pharmacists only.</p>
        </div>
    )
}
