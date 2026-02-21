import { html } from 'htm/preact'
import { filteredTags, selectedTags, tagSearch, sidebarOpen, toggleTag } from '../lib/store.js'
import { formatNum, tagColor } from '../lib/utils.js'

const CHECK_ICON = html`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>`

function TagItem({ name, count }) {
  const isSelected = selectedTags.value.includes(name)
  return html`
    <div class="tag-item ${isSelected ? 'selected' : ''}"
         onClick=${() => toggleTag(name)}>
      <div class="tag-checkbox" style="border-color: ${tagColor(name)}">
        ${CHECK_ICON}
      </div>
      <span class="tag-name" style="color: ${tagColor(name)}">${name}</span>
      <span class="tag-count">${formatNum(count)}</span>
    </div>
  `
}

export function TagSidebar() {
  if (!sidebarOpen.value) return null

  const tagsList = filteredTags.value

  return html`
    <aside class="sidebar">
      <div class="sidebar-header">
        <span class="sidebar-title">Tags</span>
        <span style="font-size: 11px; color: var(--text-muted)">${tagsList.length}</span>
      </div>
      <div class="sidebar-search">
        <input type="text"
               placeholder="Filter tags..."
               value=${tagSearch.value}
               onInput=${(e) => { tagSearch.value = e.target.value }} />
      </div>
      <div class="tag-list">
        ${tagsList.map(t => html`
          <${TagItem} key=${t.name} name=${t.name} count=${t.count} />
        `)}
        ${tagsList.length === 0 && html`
          <div style="padding: 16px; text-align: center; color: var(--text-muted); font-size: 12px;">
            No tags found
          </div>
        `}
      </div>
    </aside>
  `
}
