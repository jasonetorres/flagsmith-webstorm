const STORAGE_KEY = 'flagsmith-demo-identity'

export function getOrCreateIdentity() {
  const existing = window.localStorage.getItem(STORAGE_KEY)
  if (existing) return existing
  const id = `demo-${crypto.randomUUID()}`
  window.localStorage.setItem(STORAGE_KEY, id)
  return id
}
