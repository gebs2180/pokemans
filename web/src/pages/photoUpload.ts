import { apiPath, uploadToStorage, useApi, type PhotoView, type Side, type UploadSlot } from '../api'

type Api = ReturnType<typeof useApi>

/** The step an upload is on, named so a slow phone upload doesn't look stuck (review finding 13). */
export type UploadStep = 'UPLOADING' | 'CHECKING'
export const STEP_WORDS: Record<UploadStep, string> = {
  UPLOADING: 'Uploading…',
  CHECKING: 'Checking focus and glare…',
}

/** Sends one photo of a card's side and has it checked; says each step as it starts. */
export async function uploadPhoto(api: Api, orgId: string, cardId: string, side: Side, file: File, onStep: (step: UploadStep) => void) {
  onStep('UPLOADING')
  const slot = await api<UploadSlot>('POST', apiPath`/api/orgs/${orgId}/cards/${cardId}/photos`, {
    side,
    // Some browsers leave the type blank for a format they don't know: the API's answer says what to do.
    contentType: file.type || 'application/octet-stream',
    sizeBytes: file.size,
  })
  await uploadToStorage(slot, file)
  onStep('CHECKING')
  return api<PhotoView>('POST', apiPath`/api/orgs/${orgId}/photos/${slot.photoId}/confirm`)
}

/** The two files of a two-photo choice, else null. */
export function pairOf(list: FileList | null | undefined): [File, File] | null {
  return list && list.length >= 2 ? [list[0], list[1]] : null
}
