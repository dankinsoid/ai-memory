import { html } from 'htm/preact'
import { stats } from '../lib/store.js'
import { formatNum } from '../lib/utils.js'

export function StatBar() {
  const s = stats.value
  return html`
    <div class="statbar">
      <div class="stat-item">
        <span class="stat-value">${formatNum(s.factCount)}</span> facts
      </div>
      <div class="stat-item">
        <span class="stat-value">${formatNum(s.tagCount)}</span> tags
      </div>
      <div class="stat-item">
        <span class="stat-value">${formatNum(s.edgeCount)}</span> edges
      </div>
      <div class="stat-item">
        tick <span class="stat-value">${formatNum(s.tick)}</span>
      </div>
    </div>
  `
}
