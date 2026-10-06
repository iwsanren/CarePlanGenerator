import { test, expect, type Page } from '@playwright/test'
import fs from 'node:fs'

async function login(page: Page, username: string, password: string) {
  await page.goto('/orders')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(password)
  const apiRequest = page.waitForRequest(request => request.url().includes('/api/v1/orders')
    && !!request.headers()['authorization'])
  await page.locator('#kc-login').click()
  const captured = await apiRequest
  await expect(page.getByRole('heading', { name: 'Orders', exact: true })).toBeVisible()
  return captured.headers()['authorization']
}

test('real Keycloak login, role enforcement, business workflow, refresh, download and logout', async ({ page, request }) => {
  const auth = await login(page, 'assistant', process.env.CAREPLAN_ASSISTANT_PASSWORD || 'AssistantDemo123!')
  await expect(page.getByText('assistant · Assistant')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Export CSV' })).toHaveCount(0)
  const forbiddenDownload = await page.evaluate(async () => {
    const api = (await import('/src/services/api.ts')).default
    try { await api.get('/export', { responseType: 'blob' }); return null }
    catch (error) {
      const auth = (await import('/src/auth/auth.ts')).getAuthState()
      return { status: (error as { status: number }).status, message: (error as Error).message, authenticated: auth.authenticated }
    }
  })
  expect(forbiddenDownload).toEqual({ status: 403, message: 'Permission denied', authenticated: true })
  const headers = { Authorization: auth }
  const suffix = Date.now().toString().slice(-6)
  const payload = {
    patientFirstName: 'Browser', patientLastName: 'Acceptance', patientMrn: suffix,
    providerName: 'Dr. Browser', providerNpi: '9000' + suffix,
    medicationName: 'AuthDemo-' + suffix, primaryDiagnosis: 'G70.00',
    additionalDiagnoses: [], medicationHistory: [], createdBySubject: 'forged-client',
  }
  expect((await request.get('/api/v1/orders')).status()).toBe(401)
  expect((await request.get('/api/v1/orders', { headers: { Authorization: auth.slice(0, -8) + 'tampered' } })).status()).toBe(401)
  const created = await request.post('/api/v1/orders', { headers, data: payload })
  expect(created.status()).toBe(201)
  const order = await created.json()
  // Exercise the existing React form and its authenticated Axios submission.
  await page.goto('/')
  for (const field of ['patientFirstName', 'patientLastName', 'patientMrn', 'providerName', 'providerNpi', 'primaryDiagnosis'])
    await page.locator(`input[name="${field}"]`).fill(payload[field as keyof typeof payload] as string)
  await page.locator('input[name="medicationName"]').fill(payload.medicationName + '-UI')
  const formResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/orders')
    && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Create Order', exact: true }).click()
  expect((await formResponse).status()).toBe(201)
  await expect(page.getByRole('heading', { name: /^Order #/ })).toBeVisible()
  for (const path of [`/orders/${order.id}/regenerate`, `/orders/${order.id}/careplan/upload`]) {
    expect((await request.post('/api/v1' + path, { headers, multipart: { content: 'forbidden' } })).status()).toBe(403)
  }
  for (const path of ['/export', '/reports/orders/export'])
    expect((await request.get('/api/v1' + path, { headers })).status()).toBe(403)
  for (const path of [`/patients/${order.patientId}`, `/providers/${order.providerId}`])
    expect((await request.delete('/api/v1' + path, { headers })).status()).toBe(403)

  await expect.poll(async () => {
    const response = await request.get(`/api/v1/orders/${order.id}/status`, { headers })
    return (await response.json()).status.toLowerCase()
  }, { timeout: 30000 }).toBe('completed')
  await page.goto(`/orders/${order.id}`)
  await expect(page.getByRole('button', { name: 'Download', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Regenerate', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Upload New', exact: true })).toHaveCount(0)
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download', exact: true }).click()
  const download = await downloadPromise
  expect(await download.failure()).toBeNull()

  // Force expiry of the in-memory access token. SDK must use the real refresh token.
  const refreshed = await page.evaluate(async () => {
    const module = await import('/src/auth/auth.ts')
    const before = module.keycloak.tokenParsed?.iat
    module.keycloak.tokenParsed!.exp = 0
    await module.authorization()
    return { before, expires: module.keycloak.tokenParsed?.exp, now: Date.now() / 1000 }
  })
  expect(refreshed.expires).toBeGreaterThan(refreshed.now)
  await page.screenshot({ path: 'test-results/assistant.png', fullPage: true })
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
  await expect(page.getByText('Browser Acceptance')).toHaveCount(0)

  const pharmacist = await login(page, 'pharmacist', process.env.CAREPLAN_PHARMACIST_PASSWORD || 'PharmacistDemo123!')
  const pharmacyHeaders = { Authorization: pharmacist }
  await expect(page.getByRole('button', { name: 'Export CSV' })).toBeVisible()
  const exportDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Export CSV' }).click()
  expect(await (await exportDownload).failure()).toBeNull()
  expect((await request.get('/api/v1/reports/orders/export', { headers: pharmacyHeaders })).status()).toBe(200)
  await page.goto(`/orders/${order.id}`)
  await expect(page.getByRole('button', { name: 'Regenerate', exact: true })).toBeVisible()
  // Role permission does not override the pre-existing provider dependency constraint.
  expect((await request.delete(`/api/v1/providers/${order.providerId}`, { headers: pharmacyHeaders })).status()).toBe(409)
  const standalone = await request.post('/api/v1/providers', { headers: pharmacyHeaders,
    data: { name: 'Acceptance removable ' + suffix, npi: '8000' + suffix } })
  expect(standalone.status()).toBe(201)
  expect((await request.delete(`/api/v1/providers/${(await standalone.json()).id}`, { headers: pharmacyHeaders })).status()).toBe(204)
  const regeneration = page.waitForResponse(response => response.url().endsWith(`/orders/${order.id}/regenerate`))
  await page.getByRole('button', { name: 'Regenerate', exact: true }).click()
  expect((await regeneration).status()).toBe(200)
  await expect.poll(async () => (await (await request.get(`/api/v1/orders/${order.id}/status`, { headers: pharmacyHeaders })).json()).status.toLowerCase(), { timeout: 30000 }).toBe('completed')
  await expect(page.getByRole('button', { name: 'Upload New', exact: true })).toBeVisible({ timeout: 30000 })
  await page.getByRole('button', { name: 'Upload New', exact: true }).click()
  await page.getByPlaceholder('Paste the full care plan content here').fill(
    'Problem list\nGoals\nPharmacist interventions\nMonitoring plan\nAcceptance manual replacement')
  const textUpload = page.waitForResponse(response => response.url().endsWith(`/orders/${order.id}/careplan/upload`))
  await page.getByRole('button', { name: 'Upload', exact: true }).click()
  expect((await textUpload).status()).toBe(200)
  await expect(page.getByText('Acceptance manual replacement', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: 'Upload New', exact: true }).click()
  await page.getByRole('button', { name: 'Upload File', exact: true }).click()
  await page.locator('input[type="file"]').setInputFiles({ name: 'local-acceptance.txt', mimeType: 'text/plain',
    buffer: Buffer.from('Problem list\nGoals\nPharmacist interventions\nMonitoring plan\nAcceptance file replacement') })
  const fileUpload = page.waitForResponse(response => response.url().endsWith(`/orders/${order.id}/careplan/upload`))
  await page.getByRole('button', { name: 'Upload', exact: true }).click()
  expect((await fileUpload).status()).toBe(200)
  await expect(page.getByRole('heading', { name: 'Upload Care Plan' })).toHaveCount(0)
  await expect(page.getByText('Acceptance file replacement', { exact: false })).toBeVisible()
  // Duplicate detection remains active for an authenticated caller.
  expect((await request.post('/api/v1/orders', { headers: pharmacyHeaders, data: payload })).status()).toBe(409)
  await page.reload()
  await expect(page.getByText('Monitoring plan', { exact: false })).toBeVisible()
  await page.screenshot({ path: 'test-results/pharmacist.png', fullPage: true })
  fs.writeFileSync('test-results/demo-order.json', JSON.stringify({ id: order.id, patientId: order.patientId, providerId: order.providerId,
    assistantSubject: JSON.parse(Buffer.from(auth.split('.')[1], 'base64url').toString()).sub,
    pharmacistSubject: JSON.parse(Buffer.from(pharmacist.split('.')[1], 'base64url').toString()).sub }, null, 2))
  // A failed refresh must also cancel a request which is already in flight.
  await page.route('**/api/v1/orders?acceptance-delayed=true', () => { /* Hold until session cancellation. */ })
  const delayedRequest = page.waitForRequest(r => r.url().includes('acceptance-delayed=true'))
  await page.evaluate(async () => {
    const api = (await import('/src/services/api.ts')).default
    ;(window as Window & { acceptancePending?: Promise<string> }).acceptancePending = api
      .get('/orders?acceptance-delayed=true').then(() => 'unexpected response', (error: { code?: string }) => error.code || 'other error')
  })
  await delayedRequest
  // A failed refresh must clear authentication and cached clinical data.
  await page.evaluate(async () => {
    const module = await import('/src/auth/auth.ts')
    module.keycloak.tokenParsed!.exp = 0
    module.keycloak.refreshToken = 'invalid-refresh-token'
    try { await module.authorization() } catch { /* Expected session failure. */ }
  })
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: `Order #${order.id}` })).toHaveCount(0)
  expect(await page.evaluate(() => (window as Window & { acceptancePending?: Promise<string> }).acceptancePending))
    .toBe('ERR_CANCELED')
})

test('real identity without a pharmacy role is denied and admin account management stays separate', async ({ page, request }) => {
  const identityUrl = process.env.KEYCLOAK_PUBLIC_URL || 'http://localhost:8081'
  const adminResponse = await request.post(identityUrl + '/realms/master/protocol/openid-connect/token', {
    form: { client_id: 'admin-cli', grant_type: 'password', username: process.env.KEYCLOAK_ADMIN_USER || 'admin',
      password: process.env.KEYCLOAK_ADMIN_PASSWORD || 'local-admin-only' },
  })
  expect(adminResponse.status()).toBe(200)
  const adminHeaders = { Authorization: 'Bearer ' + (await adminResponse.json()).access_token }
  const username = 'acceptance-outsider-' + Date.now()
  const password = 'LocalAcceptanceOnly-' + Date.now() + '!'
  const created = await request.post(identityUrl + '/admin/realms/careplan/users', {
    headers: adminHeaders, data: { username, enabled: true, emailVerified: true,
      email: username + '@careplan.local', firstName: 'Acceptance', lastName: 'Outsider',
      credentials: [{ type: 'password', value: password, temporary: false }] },
  })
  expect(created.status()).toBe(201)
  const userLocation = created.headers()['location']
  try {
    await page.goto('/orders')
    await page.getByRole('button', { name: 'Sign in', exact: true }).click()
    await page.locator('#username').fill(username)
    await page.locator('#password').fill(password)
    await page.locator('#kc-login').click()
    await expect(page.getByText('This account has no pharmacy staff role. Contact your administrator.')).toBeVisible()
    const token = await page.evaluate(async () => (await import('/src/auth/auth.ts')).keycloak.token)
    expect((await request.get('/api/v1/orders', { headers: { Authorization: 'Bearer ' + token } })).status()).toBe(403)
    expect((await request.get('/api/v1/orders', { headers: adminHeaders })).status()).toBe(401)
    await page.getByRole('button', { name: 'Sign out', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
  } finally {
    // Delete only the test account this test just created, never a demo or existing user.
    expect((await request.delete(userLocation, { headers: adminHeaders })).status()).toBe(204)
  }
})
