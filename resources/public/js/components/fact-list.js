import { html } from 'htm/preact'
import { useRef, useEffect } from 'preact/hooks'
import { facts, factsTotal, factsLoading, factsOffset, hasMore, selectedTags, searchQuery, sortBy } from '../lib/store.js'
import { fetchFacts } from '../lib/api.js'
import { formatNum } from '../lib/utils.js'
import { FactCard } from './fact-card.js'

const PAGE_SIZE = 50

async function loadFacts(append = false) {
  if (factsLoading.value) return
  const tags = selectedTags.value
  const query = searchQuery.value
  if (!tags.length && !query) {
    // No filter: show all, sorted
  }
  factsLoading.value = true
  try {
    const offset = append ? factsOffset.value : 0
    const data = await fetchFacts({
      tags,
      query,
      sortBy: sortBy.value,
      limit: PAGE_SIZE,
      offset
    })
    const result = data.results?.[0]
    if (result) {
      if (append) {
        facts.value = [...facts.value, ...result.facts]
      } else {
        facts.value = result.facts
      }
      factsTotal.value = result.total ?? result.facts.length
      factsOffset.value = offset + result.facts.length
    }
  } catch (e) {
    console.error('Failed to load facts:', e)
  } finally {
    factsLoading.value = false
  }
}

// Re-fetch when filters change
let prevFilterKey = ''
function getFilterKey() {
  return JSON.stringify({
    tags: selectedTags.value,
    query: searchQuery.value,
    sort: sortBy.value
  })
}

export function FactList() {
  const sentinelRef = useRef(null)
  const listRef = useRef(null)

  // Watch for filter changes
  const currentKey = getFilterKey()
  if (currentKey !== prevFilterKey) {
    prevFilterKey = currentKey
    // Defer to avoid rendering issues
    setTimeout(() => loadFacts(false), 0)
  }

  // Infinite scroll
  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!sentinel) return
    const observer = new IntersectionObserver((entries) => {
      if (entries[0].isIntersecting && hasMore.value && !factsLoading.value) {
        loadFacts(true)
      }
    }, { rootMargin: '200px' })
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [])

  const factsList = facts.value
  const total = factsTotal.value
  const loading = factsLoading.value

  return html`
    <div class="fact-list" ref=${listRef}>
      ${total > 0 && html`
        <div class="fact-list-count">
          ${formatNum(factsList.length)} of ${formatNum(total)} facts
        </div>
      `}
      ${factsList.map((fact, i) => html`
        <${FactCard} key=${fact['db/id'] || fact.id || i} fact=${fact} />
      `)}
      ${!loading && factsList.length === 0 && html`
        <div class="fact-list-empty">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>
          </svg>
          <div>Select tags or search to explore facts</div>
        </div>
      `}
      <div ref=${sentinelRef} style="height: 1px" />
      ${loading && html`
        <div style="display: flex; justify-content: center; padding: 16px;">
          <div class="spinner" />
        </div>
      `}
    </div>
  `
}
