import { useEffect, useRef, type ReactNode } from 'react'

/**
 * A small confirmation dialog.
 *
 * The safe choice (Cancel) receives focus first, Escape closes it unless a
 * mutation is in flight, and the scrim is a real button so pointer and keyboard
 * users share one dismiss path.
 */
export function ConfirmDialog({
  title,
  description,
  confirmLabel,
  pendingLabel,
  pending = false,
  tone = 'primary',
  error,
  onConfirm,
  onCancel,
  children,
}: {
  title: string
  description?: string
  confirmLabel: string
  pendingLabel?: string
  pending?: boolean
  tone?: 'primary' | 'danger'
  error?: string | null
  onConfirm: () => void
  onCancel: () => void
  children?: ReactNode
}) {
  const cancelRef = useRef<HTMLButtonElement>(null)
  const onCancelRef = useRef(onCancel)
  const pendingRef = useRef(pending)

  useEffect(() => {
    onCancelRef.current = onCancel
    pendingRef.current = pending
  }, [onCancel, pending])

  // Mount-only: preserve focus and body scroll, and install one Escape handler.
  useEffect(() => {
    const previousFocus =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    cancelRef.current?.focus()
    document.body.style.overflow = 'hidden'

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !pendingRef.current) {
        onCancelRef.current()
      }
    }
    document.addEventListener('keydown', onKeyDown)

    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
      previousFocus?.focus()
    }
  }, [])

  return (
    <div className="modal-layer">
      <button
        type="button"
        className="modal__scrim"
        aria-label="Close dialog"
        tabIndex={-1}
        onClick={() => {
          if (!pending) {
            onCancel()
          }
        }}
      />

      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        aria-describedby={description ? 'confirm-dialog-description' : undefined}
      >
        <h2 className="modal__title" id="confirm-dialog-title">
          {title}
        </h2>
        {description ? (
          <p className="modal__description" id="confirm-dialog-description">
            {description}
          </p>
        ) : null}

        {children ? <div className="modal__body">{children}</div> : null}

        {error ? (
          <div className="alert alert--danger modal__error" role="alert">
            {error}
          </div>
        ) : null}

        <div className="modal__actions">
          <button
            ref={cancelRef}
            type="button"
            className="btn"
            onClick={onCancel}
            disabled={pending}
          >
            Cancel
          </button>
          <button
            type="button"
            className={`btn ${tone === 'danger' ? 'btn--danger' : 'btn--primary'}`}
            onClick={onConfirm}
            disabled={pending}
          >
            {pending ? (pendingLabel ?? 'Working…') : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
