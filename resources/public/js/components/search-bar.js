import { html } from 'htm/preact'
import { useRef, useEffect } from 'preact/hooks'
import { searchQuery, factsOffset, facts, factsTotal } from '../lib/store.js'
import { debounce } from '../lib/utils.js'

const SEARCH_ICON = html`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>`

const debouncedSetQuery = debounce((val) => {
  searchQuery.value = val
  factsOffset.value = 0
  facts.value = []
  factsTotal.value = 0
}, 300)

export function SearchBar() {
  const inputRef = useRef(null)

  useEffect(() => {
    const handler = (e) => {
      if (e.key === '/' && !e.ctrlKey && !e.metaKey && document.activeElement?.tagName !== 'INPUT') {
        e.preventDefault()
        inputRef.current?.focus()
      }
      if (e.key === 'Escape' && document.activeElement === inputRef.current) {
        inputRef.current.blur()
      }
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [])

  return html`
    <div class="search-input-wrap">
      ${SEARCH_ICON}
      <input ref=${inputRef}
             class="search-input"
             type="text"
             placeholder="Search facts..."
             value=${searchQuery.value}
             onInput=${(e) => debouncedSetQuery(e.target.value)} />
      <span class="search-kbd">/</span>
    </div>
  `
}
