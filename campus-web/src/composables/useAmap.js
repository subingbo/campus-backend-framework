import AMapLoader from '@amap/amap-jsapi-loader'

const centerLng = Number(import.meta.env.VITE_MAP_CENTER_LNG || 113.384128)
const centerLat = Number(import.meta.env.VITE_MAP_CENTER_LAT || 23.045678)

function escapeHtml(text) {
  return String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

export function createMarkerContent(name, isActive = false) {
  const safeName = escapeHtml(name || '地点')
  const activeClass = isActive ? ' campus-place-marker--active' : ''
  return `<div class="campus-place-marker${activeClass}">
    <span class="campus-place-marker__dot"></span>
    <span class="campus-place-marker__label">${safeName}</span>
  </div>`
}

export function createMyLocationContent() {
  return `<div class="campus-my-location-marker">
    <span class="campus-my-location-marker__dot"></span>
    <span class="campus-my-location-marker__label">我的位置</span>
  </div>`
}

export async function loadAmap(container, onClick) {
  const key = import.meta.env.VITE_AMAP_KEY
  if (!key) {
    throw new Error('请在 campus-web/.env 中配置 VITE_AMAP_KEY')
  }
  window._AMapSecurityConfig = { securityJsCode: import.meta.env.VITE_AMAP_SECURITY || '' }
  const AMap = await AMapLoader.load({
    key,
    version: '2.0',
    plugins: ['AMap.Scale', 'AMap.ToolBar', 'AMap.Geolocation']
  })
  const map = new AMap.Map(container, {
    zoom: 16,
    center: [centerLng, centerLat],
    viewMode: '2D'
  })
  map.addControl(new AMap.Scale())
  map.addControl(new AMap.ToolBar({ position: 'RB' }))
  if (onClick) {
    map.on('click', (e) => onClick(e.lnglat.getLng(), e.lnglat.getLat()))
  }
  return { AMap, map, geolocation: createGeolocation(AMap) }
}

/**
 * 内置 UI 全部关闭：标记与镜头由 createPlaceMarkerController / 视图自管。
 * 初始化失败只降级掉定位能力，不能让整张地图不可用。
 */
function createGeolocation(AMap) {
  try {
    return new AMap.Geolocation({
      enableHighAccuracy: true,
      timeout: 10000,
      convert: true,
      showButton: false,
      showMarker: false,
      showCircle: false,
      panToLocation: false,
      zoomToAccuracy: false
    })
  } catch (e) {
    console.warn('高德定位组件初始化失败，定位能力不可用', e)
    return null
  }
}

const SECURE_CONTEXT_HINT = '当前页面不是 HTTPS/localhost，手机浏览器会限制精确定位'
const GEOLOCATION_ERROR_CODES = ['PERMISSION_DENIED', 'TIMEOUT', 'POSITION_UNAVAILABLE', 'NOT_SUPPORTED']

function secureContextHint() {
  return typeof window !== 'undefined' && window.isSecureContext === false ? SECURE_CONTEXT_HINT : ''
}

function createLocationError(code, message) {
  const error = new Error(message)
  error.code = code
  const hint = secureContextHint()
  if (hint) error.hint = hint
  return error
}

function toLocationError(result) {
  const code = GEOLOCATION_ERROR_CODES.includes(result?.info) ? result.info : 'UNKNOWN'
  return createLocationError(code, result?.message || '定位失败')
}

/**
 * 高德定位适配器：把 AMap.Geolocation 收敛成 useLocation 约定的 provider，
 * 返回 GCJ-02 坐标（convert: true 已完成 WGS84 → GCJ-02 偏移修正），与高德底图一致。
 * @param {object} geolocation AMap.Geolocation 实例，未就绪时传 null
 */
export function createAmapLocationProvider(geolocation) {
  return {
    getCurrentPosition() {
      return new Promise((resolve, reject) => {
        if (!geolocation) {
          reject(createLocationError('PROVIDER_UNAVAILABLE', '定位组件未就绪'))
          return
        }
        geolocation.getCurrentPosition((status, result) => {
          if (status === 'complete' && result?.position) {
            resolve({
              lng: result.position.getLng(),
              lat: result.position.getLat(),
              accuracy: Number(result.accuracy) || 0,
              locationType: result.location_type || ''
            })
            return
          }
          reject(toLocationError(result))
        })
      })
    }
  }
}

/**
 * @param {import('@amap/amap-jsapi-loader').AMap} AMap
 * @param {AMap.Map} map
 * @param {(place: object) => void} onMarkerClick
 */
export function createPlaceMarkerController(AMap, map, onMarkerClick) {
  const markerById = new Map()
  const placeById = new Map()
  let markers = []
  let activePlaceId = null
  let myLocation = null
  let myLocationMarker = null

  function applyMarkerStyle(marker, place, isActive) {
    marker.setContent(createMarkerContent(place.name, isActive))
    marker.setzIndex(isActive ? 200 : 100)
  }

  /** 「我的位置」不是已保存地点，单独挂在覆盖物之外由本控制器维护 */
  function attachMyLocation() {
    if (!myLocation) return
    if (!myLocationMarker) {
      myLocationMarker = new AMap.Marker({
        position: [myLocation.lng, myLocation.lat],
        content: createMyLocationContent(),
        anchor: 'center',
        zIndex: 300
      })
    } else {
      myLocationMarker.setPosition([myLocation.lng, myLocation.lat])
    }
    map.add(myLocationMarker)
  }

  function render(places, { fitView = false } = {}) {
    map.clearMap()
    markers = []
    markerById.clear()
    placeById.clear()

    for (const place of places) {
      const isActive = activePlaceId === place.id
      const marker = new AMap.Marker({
        position: [Number(place.longitude), Number(place.latitude)],
        content: createMarkerContent(place.name, isActive),
        anchor: 'bottom-center',
        zIndex: isActive ? 200 : 100
      })
      marker.on('click', () => onMarkerClick(place))
      markerById.set(place.id, marker)
      placeById.set(place.id, place)
      markers.push(marker)
    }

    map.add(markers)
    attachMyLocation()
    if (fitView && markers.length > 0) {
      map.setFitView(markers, false, [80, 80, 80, 80])
    }
  }

  function setHighlight(placeId) {
    activePlaceId = placeId ?? null
    for (const [id, marker] of markerById) {
      const place = placeById.get(id)
      if (!place) continue
      applyMarkerStyle(marker, place, id === activePlaceId)
    }
  }

  function focusPlace(place, { zoom = 18 } = {}) {
    if (!place) return
    const lng = Number(place.longitude)
    const lat = Number(place.latitude)
    map.setZoomAndCenter(zoom, [lng, lat])
    setHighlight(place.id)
  }

  function setMyLocation(location, { panTo = false, zoom = 17 } = {}) {
    if (!location) return
    myLocation = {
      lng: Number(location.lng),
      lat: Number(location.lat),
      accuracy: Number(location.accuracy) || 0,
      locationType: location.locationType || ''
    }
    attachMyLocation()
    if (panTo) {
      map.setZoomAndCenter(zoom, [myLocation.lng, myLocation.lat])
    }
  }

  function clearMyLocation() {
    if (myLocationMarker) {
      map.remove(myLocationMarker)
    }
    myLocationMarker = null
    myLocation = null
  }

  return { render, focusPlace, setHighlight, setMyLocation, clearMyLocation }
}

/** @deprecated 请使用 createPlaceMarkerController */
export function renderMarkers(AMap, map, places, onMarkerClick) {
  const ctrl = createPlaceMarkerController(AMap, map, onMarkerClick)
  ctrl.render(places, { fitView: true })
  return ctrl
}
