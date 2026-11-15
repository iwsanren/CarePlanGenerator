import type { ReactNode } from 'react'
import { NoAccess } from '@/components/NoAccess'
import { useAuth } from './auth'

/**
 * Route guard, layer 1 of 2: a non-pharmacist gets a message and the page below is never rendered, so it
 * never fetches (and its lazily loaded chunk is never downloaded). The role comes from the token held in
 * the browser, which a user can tamper with, so this is only about not showing a dead end.
 * Layer 2 is the backend (URL rule + @PreAuthorize on the service): it answers 403 whatever the UI does.
 */
export function RequirePharmacist({ children }: { children: ReactNode }) {
    const auth = useAuth()
    return auth.pharmacist ? <>{children}</> : <NoAccess />
}
