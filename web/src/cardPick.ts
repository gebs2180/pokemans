import type { CatalogPick, CatalogPickDetail, Edition, Variant } from './api'

export const EDITION_LABEL: Record<Edition, string> = { UNLIMITED: 'Unlimited', FIRST_EDITION: '1st Edition', SHADOWLESS: 'Shadowless' }
export const VARIANT_LABEL: Record<Variant, string> = { NORMAL: 'Non-holo', HOLO: 'Holo', REVERSE_HOLO: 'Reverse holo' }
export const LANGUAGES: Record<string, string> = { en: 'English', ja: 'Japanese' }

/** The collector's pick. A null edition or variant means they weren't sure, and the admin settles it. */
export type PickedCard = {
  card: CatalogPickDetail
  edition: Edition | null
  variant: Variant | null
  stamp: string | null
  stampUnsure: boolean
}

/** The card's label from a pick, e.g. "Charizard · Base Set 4/102 · Shadowless · Holo". */
export function labelOf(p: PickedCard) {
  const c = p.card
  return [
    c.name,
    [c.setName, c.number].filter(Boolean).join(' '),
    c.language === 'en' ? null : (LANGUAGES[c.language] ?? c.language.toUpperCase()),
    p.edition && p.edition !== 'UNLIMITED' ? EDITION_LABEL[p.edition] : null,
    p.variant ? VARIANT_LABEL[p.variant] : null,
    p.stamp ? `${p.stamp} stamp` : null,
  ]
    .filter(Boolean)
    .join(' · ')
    .slice(0, 300)
}

/** What follows a catalog card's name: set · number · year · language. */
export function metaLine(c: CatalogPick) {
  return [c.setName, c.number, c.year, LANGUAGES[c.language] ?? c.language.toUpperCase()].filter((v) => v != null && v !== '').join(' · ')
}
