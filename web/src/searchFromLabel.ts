/**
 * A starting search from the collector's own words, e.g. "Charizard Base Set 4/102": the first word as
 * the name (TCGdex needs every word in the card's name) and the number as printed, slash and all.
 */
export function searchFromLabel(label: string): { name: string; number: string } {
  const printed = label.match(/\b[A-Za-z]{0,4}\d+[A-Za-z]?\s*\/\s*[A-Za-z]{0,4}\d+\b/)?.[0] ?? ''
  const name = label.replace(printed, ' ').replace(/#\S*/g, ' ').trim().split(/\s+/)[0] ?? ''
  return { name, number: printed.replace(/\s+/g, '') }
}
