import { html } from 'htm/preact'
import { selectedTags, removeTag, searchQuery, sortBy, setSort, sidebarOpen } from '../lib/store.js'
import { tagColor, tagBg } from '../lib/utils.js'
import { TagSidebar } from '../components/tag-sidebar.js'
import { FactList } from '../components/fact-list.js'
import { FactDetail } from '../components/fact-detail.js'

const SIDEBAR_ICON = html`<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect width="18" height="18" x="3" y="3" rx="2"/><path d="M9 3v18"/></svg>`

function FilterBar() {
  const tags = selectedTags.value
  const query = searchQuery.value
  const hasFilters = tags.length > 0 || query

  return html`
    <div class="filter-bar">
      <button class="btn btn-ghost" onClick=${() => { sidebarOpen.value = !sidebarOpen.value }}
              title="Toggle sidebar">
        ${SIDEBAR_ICON}
      </button>
      ${tags.map(t => html`
        <span key=${t} class="filter-chip" style="color: ${tagColor(t)}; background: ${tagBg(t)}; border-color: ${tagColor(t)}33">
          ${t}
          <span class="filter-chip-remove" onClick=${() => removeTag(t)}>×</span>
        </span>
      `)}
      ${query && html`
        <span class="filter-chip" style="border-color: rgba(126, 231, 135, 0.25); color: var(--color-blob); background: rgba(126, 231, 135, 0.1);">
          "${query}"
          <span class="filter-chip-remove" onClick=${() => { searchQuery.value = '' }}>×</span>
        </span>
      `}
      ${!hasFilters && html`
        <span style="font-size: 12px; color: var(--text-muted);">All facts</span>
      `}
      <div class="filter-sort">
        <label>sort:</label>
        <select value=${sortBy.value} onChange=${(e) => setSort(e.target.value)}>
          <option value="date">date</option>
          <option value="weight">weight</option>
        </select>
      </div>
    </div>
  `
}

export function ExploreView() {
  return html`
    <div class="main-layout">
      <${TagSidebar} />
      <div class="content-panel">
        <${FilterBar} />
        <${FactList} />
      </div>
      <${FactDetail} />
    </div>
  `
}
