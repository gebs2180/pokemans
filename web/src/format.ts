/** Display helpers shared by the pages. */

/** A dollar amount in en-US form, or a dash for none. */
export function money(amount: number | null | undefined) {
  return amount == null ? '–' : amount.toLocaleString('en-US', { style: 'currency', currency: 'USD' })
}

/** A 0..1 share as a percent with at most one decimal. */
export function percent(share: number) {
  return `${Math.round(share * 1000) / 10}%`
}

/** An enum value as lower-case words, e.g. "RECENT_CHASE" becomes "recent chase". */
export function label(value: string) {
  return value.replaceAll('_', ' ').toLowerCase()
}

/** Today as YYYY-MM-DD in the user's own time zone. */
export function today() {
  return new Date().toLocaleDateString('en-CA')
}

/** The text of a thrown value. */
export function message(err: unknown) {
  return err instanceof Error ? err.message : String(err)
}
