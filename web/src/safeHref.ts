/** The URL if it is absolute http(s), else undefined, so data never becomes a javascript: or data: link. */
export function safeHref(u: string | null | undefined): string | undefined {
  if (!u) return undefined
  try {
    const { protocol } = new URL(u)
    return protocol === 'http:' || protocol === 'https:' ? u : undefined
  } catch {
    return undefined
  }
}
