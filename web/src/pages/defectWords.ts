import type { DefectMarkView } from '../api'

/** Marks come with plain words for what they are. */
export const DEFECT_LABEL: Record<DefectMarkView['type'], string> = {
  SCRATCH: 'Scratch',
  PRINT_LINE: 'Print line',
  DENT_CREASE: 'Dent or crease',
  WHITENING: 'Whitening',
  STAIN: 'Stain',
  OTHER: 'Other mark',
}
export const SEVERITY_LABEL: Record<DefectMarkView['severity'], string> = { MINOR: 'minor', MODERATE: 'moderate', MAJOR: 'major' }

/** "Scratch, minor", plus the reviewer's note when there is one. */
export function markWords(m: DefectMarkView) {
  return `${DEFECT_LABEL[m.type]}, ${SEVERITY_LABEL[m.severity]}${m.note ? `: ${m.note}` : ''}`
}
