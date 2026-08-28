import { Routes, Route } from 'react-router-dom'
import { Layout } from './components/Layout'

interface PlaceholderProps {
  title: string
}

function Placeholder({ title }: PlaceholderProps) {
  return (
    <div className="rounded-lg border border-dashed border-gray-300 bg-white p-12 text-center">
      <p className="text-lg font-medium text-gray-500">TODO: {title}</p>
    </div>
  )
}

function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Placeholder title="New Order" />} />
        <Route path="/orders" element={<Placeholder title="Orders" />} />
        <Route path="/orders/:id" element={<Placeholder title="Order Detail" />} />
      </Routes>
    </Layout>
  )
}

export default App
