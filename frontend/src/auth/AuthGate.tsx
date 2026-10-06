import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { initializeAuth, signIn, signOut, useAuth } from './auth'

export function AuthGate({ children }: { children: React.ReactNode }) {
  const auth = useAuth()
  const client = useQueryClient()
  useEffect(() => { void initializeAuth() }, [])
  useEffect(() => {
    void client.cancelQueries()
    client.clear()
  }, [auth.subject, auth.authenticated, client])

  if (!auth.ready) return <p className="p-8">Connecting to sign-in service…</p>
  if (!auth.authenticated || !auth.staff) return (
    <main className="mx-auto mt-20 max-w-lg rounded bg-white p-8 shadow">
      <h1 className="mb-4 text-2xl font-bold">Care Plan Generator</h1>
      <p className="mb-6">{auth.error || (auth.authenticated
        ? 'This account has no pharmacy staff role. Contact your administrator.'
        : 'Sign in with your pharmacy staff account.')}</p>
      <button className="rounded bg-blue-600 px-4 py-2 text-white" onClick={() => {
        void (auth.authenticated ? signOut() : signIn())
      }}>{auth.authenticated ? 'Sign out' : 'Sign in'}</button>
    </main>
  )
  return <>{children}</>
}
