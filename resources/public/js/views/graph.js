import { html } from 'htm/preact'
import { useRef, useEffect, useCallback } from 'preact/hooks'
import { graphLoading, graphFocusNode, graphLayoutRunning, selectedFact, selectFact } from '../lib/store.js'
import { fetchTopNodes, fetchNeighborhood } from '../lib/api.js'
import { typeColor, weightColor, truncate } from '../lib/utils.js'
import { FactDetail } from '../components/fact-detail.js'

// Dynamic imports for graph libraries
let Graph, Sigma, forceAtlas2, FA2Layout

async function ensureLibs() {
  if (Graph) return
  try {
    const [gMod, sMod, faMod] = await Promise.all([
      import('https://esm.sh/graphology@0.25.4'),
      import('https://esm.sh/sigma@3.0.2'),
      import('https://esm.sh/graphology-layout-forceatlas2@0.10.1')
    ])
    Graph = gMod.default || gMod.Graph
    Sigma = sMod.Sigma || sMod.default
    forceAtlas2 = faMod.default || faMod
    FA2Layout = forceAtlas2
    console.log('Graph libs loaded:', { Graph: !!Graph, Sigma: !!Sigma, FA2: !!FA2Layout })
  } catch (e) {
    console.error('Failed to load graph libs:', e)
    throw e
  }
}

// Color map for types (hex for sigma)
const TYPE_COLORS = {
  fact: '#58a6ff',
  session: '#d2a8ff',
  blob: '#7ee787'
}

function weightToSize(w) {
  return 4 + (w || 1) * 3
}

export function GraphView() {
  const containerRef = useRef(null)
  const sigmaRef = useRef(null)
  const graphRef = useRef(null)
  const expandedRef = useRef(new Set())
  const tooltipRef = useRef(null)

  const initGraph = useCallback(async () => {
    if (!containerRef.current) return
    const { clientWidth, clientHeight } = containerRef.current
    console.log('Graph container:', clientWidth, 'x', clientHeight)
    if (!clientWidth || !clientHeight) {
      console.warn('Graph container has zero dimensions, retrying...')
      setTimeout(initGraph, 200)
      return
    }
    graphLoading.value = true

    try {
      await ensureLibs()

      // Clean up previous instance
      if (sigmaRef.current) {
        sigmaRef.current.kill()
        sigmaRef.current = null
      }

      const graph = new Graph()
      graphRef.current = graph

      // Load top nodes
      const data = await fetchTopNodes(30)
      const nodes = data.nodes || []

      nodes.forEach((n, i) => {
        const angle = (2 * Math.PI * i) / nodes.length
        graph.addNode(n.id, {
          label: truncate(n.content, 40),
          x: Math.cos(angle) * 100 + Math.random() * 20,
          y: Math.sin(angle) * 100 + Math.random() * 20,
          size: weightToSize(n['effective-weight'] || n.weight),
          color: TYPE_COLORS[n.nodeType || n.type] || TYPE_COLORS.fact,
          nodeType: n.type,
          content: n.content,
          weight: n.weight,
          effectiveWeight: n['effective-weight'],
          edgeCount: n['edge-count'] || 0,
          tags: n.tags || []
        })
      })

      // Render with Sigma
      const sigma = new Sigma(graph, containerRef.current, {
        renderLabels: true,
        labelRenderedSizeThreshold: 8,
        labelSize: 12,
        labelColor: { color: '#8b949e' },
        defaultEdgeColor: '#30363d',
        defaultEdgeType: 'line',
        stagePadding: 40,
        drawNodeHover: () => {}
      })
      sigmaRef.current = sigma

      // Hover tooltip
      sigma.on('enterNode', ({ node }) => {
        const attrs = graph.getNodeAttributes(node)
        if (tooltipRef.current) {
          const pos = sigma.graphToViewport(graph.getNodeAttributes(node))
          tooltipRef.current.style.left = `${pos.x + 15}px`
          tooltipRef.current.style.top = `${pos.y - 10}px`
          tooltipRef.current.innerHTML = `
            <strong>${attrs.nodeType}</strong> #${node}<br/>
            ${attrs.content || ''}<br/>
            <span style="color: #8b949e">w: ${(attrs.effectiveWeight ?? attrs.weight)?.toFixed(2) || '?'} · ${attrs.edgeCount} edges</span>
          `
          tooltipRef.current.classList.add('visible')
        }
      })

      sigma.on('leaveNode', () => {
        if (tooltipRef.current) {
          tooltipRef.current.classList.remove('visible')
        }
      })

      // Click to expand neighborhood
      sigma.on('clickNode', async ({ node }) => {
        const attrs = graph.getNodeAttributes(node)

        // Open detail panel
        selectFact({ id: node, content: attrs.content, type: attrs.nodeType, weight: attrs.weight, tags: attrs.tags })

        // Expand if not already expanded
        if (!expandedRef.current.has(node)) {
          expandedRef.current.add(node)
          try {
            const data = await fetchNeighborhood(node, 1, 30)
            const centerAttrs = graph.getNodeAttributes(node)

            ;(data.nodes || []).forEach(n => {
              if (!graph.hasNode(n.id)) {
                graph.addNode(n.id, {
                  label: truncate(n.content, 40),
                  x: centerAttrs.x + (Math.random() - 0.5) * 50,
                  y: centerAttrs.y + (Math.random() - 0.5) * 50,
                  size: weightToSize(n['effective-weight'] ?? n.weight),
                  color: TYPE_COLORS[n.nodeType || n.type] || TYPE_COLORS.fact,
                  nodeType: n.type,
                  content: n.content,
                  weight: n.weight,
                  effectiveWeight: n['effective-weight'],
                  edgeCount: 0,
                  tags: n.tags || []
                })
              }
            })

            ;(data.edges || []).forEach((e) => {
              if (graph.hasNode(e.source) && graph.hasNode(e.target) && !graph.hasEdge(e.source, e.target)) {
                graph.addEdge(e.source, e.target, {
                  weight: e.weight || 1,
                  size: Math.max(0.5, (e.weight || 1) * 0.8),
                  color: `rgba(88, 166, 255, ${Math.min(1, 0.2 + (e.weight || 0) * 0.3)})`
                })
              }
            })

            // Run layout briefly
            runLayout(graph, sigma)
          } catch (e) {
            console.error('Failed to expand neighborhood:', e)
          }
        }
      })

      // Load edges for initial nodes in background
      for (const n of nodes.slice(0, 10)) {
        try {
          const data = await fetchNeighborhood(n.id, 1, 15)
          expandedRef.current.add(n.id)
          ;(data.nodes || []).forEach(nn => {
            if (!graph.hasNode(nn.id)) {
              const existingNode = nodes.find(x => x.id === nn.id)
              graph.addNode(nn.id, {
                label: truncate(nn.content, 40),
                x: graph.getNodeAttribute(n.id, 'x') + (Math.random() - 0.5) * 80,
                y: graph.getNodeAttribute(n.id, 'y') + (Math.random() - 0.5) * 80,
                size: weightToSize(nn['effective-weight'] ?? nn.weight),
                color: TYPE_COLORS[nn.type] || TYPE_COLORS.fact,
                type: nn.type,
                content: nn.content,
                weight: nn.weight,
                effectiveWeight: nn['effective-weight'],
                edgeCount: 0,
                tags: nn.tags || []
              })
            }
          })
          ;(data.edges || []).forEach((e, i) => {
            const edgeId = `${e.source}-${e.target}-${i}`
            if (!graph.hasEdge(edgeId) && graph.hasNode(e.source) && graph.hasNode(e.target)) {
              graph.addEdge(e.source, e.target, {
                weight: e.weight || 1,
                size: Math.max(0.5, (e.weight || 1) * 0.8),
                color: `rgba(88, 166, 255, ${Math.min(1, 0.2 + (e.weight || 0) * 0.3)})`
              })
            }
          })
        } catch (e) {
          // Ignore individual failures
        }
      }

      runLayout(graph, sigma)

      // Handle focus node from explore view
      if (graphFocusNode.value) {
        const nodeId = String(graphFocusNode.value)
        if (graph.hasNode(nodeId)) {
          const attrs = graph.getNodeAttributes(nodeId)
          sigma.getCamera().animate({ x: attrs.x, y: attrs.y, ratio: 0.3 }, { duration: 500 })
        }
        graphFocusNode.value = null
      }
    } catch (e) {
      console.error('Graph init failed:', e)
    } finally {
      graphLoading.value = false
    }
  }, [])

  function runLayout(graph, sigma) {
    if (!FA2Layout || !graph || graph.order < 2) return
    graphLayoutRunning.value = true
    try {
      const settings = {
        iterations: 100,
        settings: {
          gravity: 1,
          scalingRatio: 5,
          strongGravityMode: true,
          barnesHutOptimize: graph.order > 100,
          slowDown: 5
        }
      }
      const positions = FA2Layout(graph, settings)
      // Apply positions
      if (positions && typeof positions === 'object') {
        Object.entries(positions).forEach(([nodeId, pos]) => {
          if (graph.hasNode(nodeId)) {
            graph.setNodeAttribute(nodeId, 'x', pos.x)
            graph.setNodeAttribute(nodeId, 'y', pos.y)
          }
        })
      }
    } catch (e) {
      console.error('Layout error:', e)
    } finally {
      graphLayoutRunning.value = false
    }
  }

  useEffect(() => {
    // Small delay ensures container has dimensions after layout
    const timer = setTimeout(initGraph, 50)
    return () => {
      clearTimeout(timer)
      if (sigmaRef.current) {
        sigmaRef.current.kill()
        sigmaRef.current = null
      }
    }
  }, [])

  const loading = graphLoading.value
  const layoutRunning = graphLayoutRunning.value

  return html`
    <div class="main-layout">
      <${FactDetail} />
      <div class="graph-container">
        <div ref=${containerRef} class="graph-canvas" />
        <div ref=${tooltipRef} class="graph-tooltip" />

        <div class="graph-controls">
          ${loading && html`<div class="graph-btn"><div class="spinner" style="width: 16px; height: 16px;" /></div>`}
          <button class="graph-btn ${layoutRunning ? 'active' : ''}"
                  title="Re-run layout"
                  onClick=${() => {
                    if (graphRef.current && sigmaRef.current) {
                      runLayout(graphRef.current, sigmaRef.current)
                    }
                  }}>
            ⟳
          </button>
          <button class="graph-btn" title="Reset zoom"
                  onClick=${() => sigmaRef.current?.getCamera()?.animatedReset()}>
            ⊡
          </button>
        </div>

        <div class="graph-legend">
          <div class="graph-legend-item">
            <div class="graph-legend-dot" style="background: ${TYPE_COLORS.fact}" />
            <span>fact</span>
          </div>
          <div class="graph-legend-item">
            <div class="graph-legend-dot" style="background: ${TYPE_COLORS.session}" />
            <span>session</span>
          </div>
          <div class="graph-legend-item">
            <div class="graph-legend-dot" style="background: ${TYPE_COLORS.blob}" />
            <span>blob</span>
          </div>
        </div>
      </div>
    </div>
  `
}
