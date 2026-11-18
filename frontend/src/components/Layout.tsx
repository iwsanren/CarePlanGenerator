import { signOut, useAuth } from '@/auth/auth'
import { Link, useLocation } from 'react-router-dom'
import { BarChart3, FileText, List, Plus } from 'lucide-react'
import { OrderSearchMemoryProvider } from '@/components/OrderSearchMemoryProvider'
import { useOrderSearchMemory } from '@/hooks/useOrderSearchMemory'
import { cn } from '@/utils/utils'

interface LayoutProps {
  children: React.ReactNode
}

/** The frame of every page. It also owns the in-memory patient name / MRN of the order search (see the provider). */
export function Layout({ children }: LayoutProps) {
  return (
    <OrderSearchMemoryProvider>
      <LayoutFrame>{children}</LayoutFrame>
    </OrderSearchMemoryProvider>
  )
}

function LayoutFrame({ children }: LayoutProps) {
  const location = useLocation()
  const auth = useAuth()
  const { clearMemory } = useOrderSearchMemory()

  // The Dashboard entry is only for pharmacists. Hiding it is a convenience; the backend enforces the role.
  const navItems = [
    { path: '/', label: 'New Order', icon: Plus, pharmacistOnly: false },
    { path: '/orders', label: 'Orders', icon: List, pharmacistOnly: false },
    { path: '/dashboard', label: 'Dashboard', icon: BarChart3, pharmacistOnly: true },
  ].filter((item) => !item.pharmacistOnly || auth.pharmacist)

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <header className="bg-white shadow-sm border-b">
        <div className="max-w-7xl mx-auto px-4 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <FileText className="h-8 w-8 text-blue-600" />
              <h1 className="text-xl font-bold text-gray-900">
                Care Plan Generator
              </h1>
            </div>
            <nav className="flex items-center gap-4">
              <span className="text-sm text-gray-600">{auth.username} · {auth.pharmacist ? 'Pharmacist' : 'Assistant'}</span>
              <button className="text-sm text-blue-600" onClick={() => { void signOut() }}>Sign out</button>
              {navItems.map((item) => {
                const Icon = item.icon
                const isActive = location.pathname === item.path
                return (
                  <Link
                    key={item.path}
                    to={item.path}
                    // The "Orders" link means a fresh list: the URL part of the search is reset by the bare path,
                    // and the patient name / MRN (not in the URL) must go too, or a filter nobody can see in the
                    // address bar would stay active. The "Back to orders" links in an order do not do this.
                    onClick={item.path === '/orders' ? clearMemory : undefined}
                    className={cn(
                      'flex items-center gap-2 px-4 py-2 rounded-lg transition-colors',
                      isActive
                        ? 'bg-blue-100 text-blue-700'
                        : 'text-gray-600 hover:bg-gray-100'
                    )}
                  >
                    <Icon className="h-4 w-4" />
                    {item.label}
                  </Link>
                )
              })}
            </nav>
          </div>
        </div>
      </header>

      {/* Main content */}
      <main className="max-w-7xl mx-auto px-4 py-8">{children}</main>
    </div>
  )
}
