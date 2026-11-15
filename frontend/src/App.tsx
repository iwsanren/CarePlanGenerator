import { lazy, Suspense } from 'react'
import { AuthGate } from './auth/AuthGate'
import { RequirePharmacist } from './auth/RequirePharmacist'
import { Routes, Route } from 'react-router-dom'
import { Layout } from './components/Layout'
import { NewOrderPage } from '@/pages/NewOrderPage'
import { OrdersPage } from '@/pages/OrdersPage'
import { OrderDetailPage } from '@/pages/OrderDetailPage'

// Route-level code splitting: Recharts (the biggest dependency) lives in the Dashboard chunk and is
// only downloaded when a pharmacist opens /dashboard, so the first screen stays small.
const DashboardPage = lazy(() => import('@/pages/DashboardPage').then((module) => ({ default: module.DashboardPage })))

function App() {
  return (
    <AuthGate><Layout>
      <Routes>
        <Route path="/" element={<NewOrderPage />} />
        <Route path="/orders" element={<OrdersPage />} />
        <Route path="/orders/:id" element={<OrderDetailPage />} />
        <Route
          path="/dashboard"
          element={
            <RequirePharmacist>
              <Suspense fallback={<p role="status" className="p-8">Loading dashboard…</p>}>
                <DashboardPage />
              </Suspense>
            </RequirePharmacist>
          }
        />
      </Routes>
    </Layout></AuthGate>
  )
}

export default App
