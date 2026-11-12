import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { UploadCarePlanModal } from './UploadCarePlanModal'

function setup(props: Partial<React.ComponentProps<typeof UploadCarePlanModal>> = {}) {
  const onClose = vi.fn()
  const onSubmit = vi.fn()
  const view = render(<UploadCarePlanModal open onClose={onClose} onSubmit={onSubmit} {...props} />)
  const fileInput = () => view.container.querySelector<HTMLInputElement>('input[type="file"]') as HTMLInputElement
  return { onClose, onSubmit, fileInput, ...view }
}

const upload = () => screen.getByRole('button', { name: /^Upload$|^Uploading…$/ })
const textArea = () => screen.getByPlaceholderText('Paste the full care plan content here')

describe('UploadCarePlanModal', () => {
  it('renders nothing while closed', () => {
    const { container } = setup({ open: false })

    expect(container).toBeEmptyDOMElement()
  })

  describe('paste-text mode', () => {
    it('is the default mode, and Upload is disabled until there is real text', async () => {
      setup()

      expect(screen.getByRole('heading', { name: 'Upload Care Plan' })).toBeInTheDocument()
      expect(upload()).toBeDisabled()
      await userEvent.type(textArea(), '   ')
      expect(upload()).toBeDisabled() // whitespace only does not count
      await userEvent.type(textArea(), 'Plan')
      expect(upload()).toBeEnabled()
    })

    it('submits the text exactly as typed', async () => {
      const { onSubmit } = setup()
      await userEvent.type(textArea(), 'Goals and monitoring')

      await userEvent.click(upload())

      expect(onSubmit).toHaveBeenCalledWith({ text: 'Goals and monitoring' })
    })

    it('Cancel calls onClose and submits nothing', async () => {
      const { onClose, onSubmit } = setup()

      await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

      expect(onClose).toHaveBeenCalledTimes(1)
      expect(onSubmit).not.toHaveBeenCalled()
    })
  })

  describe('file mode', () => {
    it('shows a file picker limited to .txt and keeps Upload disabled until a file is chosen', async () => {
      const { fileInput } = setup()

      await userEvent.click(screen.getByRole('button', { name: 'Upload File' }))

      expect(fileInput()).toHaveAttribute('accept', '.txt')
      expect(screen.queryByPlaceholderText('Paste the full care plan content here')).not.toBeInTheDocument()
      expect(upload()).toBeDisabled()
    })

    it('previews the chosen file and submits the File object (not its text)', async () => {
      const { fileInput, onSubmit } = setup()
      await userEvent.click(screen.getByRole('button', { name: 'Upload File' }))
      const file = new File(['Problem list\nGoals'], 'plan.txt', { type: 'text/plain' })

      await userEvent.upload(fileInput(), file)

      expect(await screen.findByText(/Problem list\s+Goals/)).toBeInTheDocument()
      expect(upload()).toBeEnabled()
      await userEvent.click(upload())
      expect(onSubmit).toHaveBeenCalledWith({ file })
    })

    it('cuts a long preview at 500 characters and adds an ellipsis', async () => {
      const { fileInput } = setup()
      await userEvent.click(screen.getByRole('button', { name: 'Upload File' }))

      await userEvent.upload(fileInput(), new File(['a'.repeat(600)], 'long.txt', { type: 'text/plain' }))

      expect(await screen.findByText('a'.repeat(500) + '...')).toBeInTheDocument()
    })

    it('does not add an ellipsis at exactly 500 characters', async () => {
      const { fileInput } = setup()
      await userEvent.click(screen.getByRole('button', { name: 'Upload File' }))

      await userEvent.upload(fileInput(), new File(['b'.repeat(500)], 'edge.txt', { type: 'text/plain' }))

      expect(await screen.findByText('b'.repeat(500))).toBeInTheDocument()
    })

    it('disables Upload again when the selection is cleared', async () => {
      const { fileInput } = setup()
      await userEvent.click(screen.getByRole('button', { name: 'Upload File' }))
      await userEvent.upload(fileInput(), new File(['x'], 'plan.txt', { type: 'text/plain' }))
      await screen.findByText('x')

      fireEvent.change(fileInput(), { target: { files: [] } })

      expect(upload()).toBeDisabled()
      expect(screen.queryByText('x')).not.toBeInTheDocument()
    })

    it('does not submit anything when no file is chosen', async () => {
      const { onSubmit } = setup()
      await userEvent.click(screen.getByRole('button', { name: 'Upload File' }))

      fireEvent.click(upload()) // bypass the disabled attribute to exercise the guard

      expect(onSubmit).not.toHaveBeenCalled()
    })
  })

  it('keeps the typed text when switching to file mode and back', async () => {
    setup()
    await userEvent.type(textArea(), 'half-written plan')

    await userEvent.click(screen.getByRole('button', { name: 'Upload File' }))
    await userEvent.click(screen.getByRole('button', { name: 'Paste Text' }))

    expect(textArea()).toHaveValue('half-written plan')
  })

  it('while submitting: shows "Uploading…" and disables Upload and Cancel', async () => {
    setup({ submitting: true })

    expect(screen.getByRole('button', { name: 'Uploading…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
  })
})
