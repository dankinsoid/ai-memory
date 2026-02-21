import { html } from 'htm/preact'
import { selectedFact, selectFact } from '../lib/store.js'
import { weightColor, timeAgo, tagColor, tagBg } from '../lib/utils.js'

export function FactCard({ fact }) {
  const content = fact['node/content'] || fact.content || ''
  const tags = fact['node/tag-refs'] || fact.tags || []
  const weight = fact['node/weight'] || fact.weight
  const updatedAt = fact['node/updated-at'] || fact.updated_at
  const id = fact['db/id'] || fact.id
  const isSelected = selectedFact.value && (selectedFact.value['db/id'] || selectedFact.value.id) === id

  const tagNames = tags.map(t => typeof t === 'string' ? t : (t['tag/name'] || t.name || ''))

  return html`
    <div class="fact-card ${isSelected ? 'selected' : ''}"
         style="--weight-color: ${weightColor(weight)}"
         onClick=${() => selectFact(fact)}>
      <div class="fact-card-body">
        <div class="fact-card-content">${content}</div>
        ${tagNames.length > 0 && html`
          <div class="fact-card-meta">
            ${tagNames.map(t => html`
              <span key=${t} class="fact-card-tag" style="color: ${tagColor(t)}; background: ${tagBg(t)}">${t}</span>
            `)}
          </div>
        `}
      </div>
      <div class="fact-card-right">
        ${weight != null && html`
          <span class="fact-card-weight" style="color: ${weightColor(weight)}">
            ${weight.toFixed(1)}
          </span>
        `}
        ${updatedAt && html`<span class="fact-card-age">${timeAgo(updatedAt)}</span>`}
        ${id && html`<span class="fact-card-id">#${id}</span>`}
      </div>
    </div>
  `
}
