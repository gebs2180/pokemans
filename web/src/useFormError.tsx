import { useId } from 'react'

/**
 * A form's error after it was sent, linked to the form with aria-describedby so a screen reader reads it with the form.
 * Returns the id to put on the form, and the message to render.
 */
export function useFormError(error: string | null) {
  const id = useId()
  return {
    describedBy: error ? id : undefined,
    message: error ? (
      <p id={id} className="error" role="alert">
        {error}
      </p>
    ) : null,
  }
}
