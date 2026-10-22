/**
 * 群规划公开地图：只处理 locationVisible=true 且有坐标的站点。
 *
 * 路线配对必须按原始相邻站点对 (i, i+1) 判断：
 * A 公开、B 私有、C 公开时，A→B 与 B→C 都跳过，绝不把 A→B→C 压缩成 A→C。
 */

function hasPublicLocation(stop) {
  if (!stop || stop.locationVisible !== true) return false
  if (stop.longitude == null || stop.latitude == null) return false
  const lng = Number(stop.longitude)
  const lat = Number(stop.latitude)
  return Number.isFinite(lng) && Number.isFinite(lat)
}

export function publicMapStops(stops) {
  if (!Array.isArray(stops)) return []
  return stops
    .map((stop, index) => ({ stop, index }))
    .filter(({ stop }) => hasPublicLocation(stop))
    .map(({ stop, index }) => ({
      id: `stop-${stop.seq ?? index + 1}`,
      name: stop.name || `站点 ${index + 1}`,
      longitude: Number(stop.longitude),
      latitude: Number(stop.latitude),
      seq: stop.seq ?? index + 1
    }))
}

export function buildPublicRoutePairs(stops) {
  if (!Array.isArray(stops)) return []
  const pairs = []
  for (let i = 1; i < stops.length; i += 1) {
    const from = stops[i - 1]
    const to = stops[i]
    if (!hasPublicLocation(from) || !hasPublicLocation(to)) continue
    pairs.push({
      fromIndex: i - 1,
      toIndex: i,
      fromSeq: from.seq ?? i,
      toSeq: to.seq ?? i + 1,
      from: { longitude: Number(from.longitude), latitude: Number(from.latitude), name: from.name },
      to: { longitude: Number(to.longitude), latitude: Number(to.latitude), name: to.name },
      mode: to.transportMode || 'WALK'
    })
  }
  return pairs
}

export function hiddenStops(stops) {
  if (!Array.isArray(stops)) return []
  return stops.filter((stop) => stop && stop.locationVisible !== true)
}
