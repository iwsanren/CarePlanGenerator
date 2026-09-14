import { Routes, Route } from 'react-router-dom'
import { Layout } from './components/Layout'
import { NewOrderPage } from '@/pages/NewOrderPage'
import { OrdersPage } from '@/pages/OrdersPage'
import { OrderDetailPage } from '@/pages/OrderDetailPage'

function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<NewOrderPage />} />
        <Route path="/orders" element={<OrdersPage />} />
        <Route path="/orders/:id" element={<OrderDetailPage />} />
      </Routes>
    </Layout>
  )
}

export default App
