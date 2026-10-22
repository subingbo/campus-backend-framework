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
  if (lng == null || lat == null) return null
  return { lng: Number(lng), lat: Number(lat) }
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
