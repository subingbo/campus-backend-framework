/**
 * 高德 URI 导航：项目坐标统一为 GCJ-02，可直接传给高德，无需转换。
 * 有起点时打开路线导航；没有起点时降级为地点标记页，避免跳转失败。
 */

const MODE_MAP = {
  WALK: 'walk',
  RIDE: 'ride',
  DRIVE: 'car',
  TAXI: 'car',
  TRANSIT: 'bus'
}

export const AMAP_MODE_LABEL = {
  WALK: '步行',
  RIDE: '骑行',
  DRIVE: '驾车',
  TAXI: '打车',
  TRANSIT: '公交'
}

function coordOf(point) {
  if (!point) return null
  const lng = point.lng ?? point.longitude
  const lat = point.lat ?? point.latitude
  if (lng == null || lat == null || String(lng).trim() === '' || String(lat).trim() === '') return null
  const coord = { lng: Number(lng), lat: Number(lat) }
  if (!Number.isFinite(coord.lng) || !Number.isFinite(coord.lat)
      || Math.abs(coord.lng) > 180 || Math.abs(coord.lat) > 90) return null
  return coord
}

function placeText(point, fallback) {
  return encodeURIComponent(point?.name || fallback)
}

export function buildAmapNavigationUrl({ from, to, mode } = {}) {
  const target = coordOf(to)
  if (!target) return ''
  const targetText = `${target.lng.toFixed(6)},${target.lat.toFixed(6)},`
    + placeText(to, '目的地')
  const start = coordOf(from)
  if (!start) {
    return `https://uri.amap.com/marker?position=${target.lng.toFixed(6)},${target.lat.toFixed(6)}`
      + `&name=${placeText(to, '目的地')}&coordinate=gaode&src=gdufs-campus&callnative=1`
  }
  const startText = `${start.lng.toFixed(6)},${start.lat.toFixed(6)},`
    + placeText(from, '我的位置')
  return `https://uri.amap.com/navigation?from=${startText}&to=${targetText}`
    + `&mode=${MODE_MAP[mode] || 'walk'}&policy=0&src=gdufs-campus&callnative=1`
}

export function openAmapNavigation(options) {
  const url = buildAmapNavigationUrl(options)
  if (url) window.open(url, '_blank', 'noopener')
  return url
}

/** Only structured route events supply coordinates/mode; reply prose is never parsed. */
export function buildGeoRouteLinks(events = []) {
  const places = events.flatMap((event) => event?.places || [])
  const seen = new Set()
  const links = []
  const pointOf = (pair) => Array.isArray(pair) ? coordOf({ lng: pair[0], lat: pair[1] }) : null
  const nameOf = (point, fallback) => places.find((place) => {
    const coord = coordOf(place)
    return coord && Math.abs(coord.lng - point.lng) < 0.000001 && Math.abs(coord.lat - point.lat) < 0.000001
  })?.name || fallback
  for (const event of events) {
    if (event?.type !== 'ROUTE' || !event.route) continue
    const route = event.route
    const from = pointOf(route.from)
    const to = pointOf(route.to)
    if (!from || !to) continue
    const mode = route.mode || 'WALK'
    const key = `${from.lng},${from.lat}:${to.lng},${to.lat}:${mode}`
    if (seen.has(key)) continue
    seen.add(key)
    from.name = nameOf(from, '路线起点')
    to.name = nameOf(to, '路线终点')
    links.push({
      key, fromName: from.name, toName: to.name,
      modeLabel: AMAP_MODE_LABEL[mode] || '步行',
      minutes: route.durationSeconds > 0 ? Math.ceil(route.durationSeconds / 60) : null,
      distanceMeters: route.distanceMeters > 0 ? Math.round(route.distanceMeters) : null,
      estimated: route.estimated === true || route.provider === 'estimate',
      url: buildAmapNavigationUrl({ from, to, mode })
    })
  }
  return links
}
