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

/**
 * 生成可点击的标记 DOM。AMap 的 marker.on('click') 在部分环境/自定义
 * content 下不稳定，直接给内容绑定点击并阻止冒泡，避免误触发地图点击。
 */
export function createPlaceMarkerElement(place, isActive = false, onSelect = null) {
  const el = document.createElement('div')
  el.className = `campus-place-marker${isActive ? ' campus-place-marker--active' : ''}`
  const dot = document.createElement('span')
  dot.className = 'campus-place-marker__dot'
  const label = document.createElement('span')
  label.className = 'campus-place-marker__label'
  label.textContent = place?.name || '地点'
  el.append(dot, label)
  if (typeof onSelect === 'function') {
    el.addEventListener('click', (event) => {
      event.stopPropagation()
      onSelect(place)
    })
  }
  return el
}

export function createMyLocationContent() {
  return `<div class="campus-my-location-marker">
    <span class="campus-my-location-marker__dot"></span>
    <span class="campus-my-location-marker__label">我的位置</span>
  </div>`
}

export async function loadAmap(container, onClick, options = {}) {
  const {
    controls = true,
    mapStyle = import.meta.env.VITE_MAP_STYLE || 'amap://styles/fresh'
  } = options
  const key = import.meta.env.VITE_AMAP_KEY
  if (!key) {
    throw new Error('请在 campus-web/.env 中配置 VITE_AMAP_KEY')
  }
  window._AMapSecurityConfig = { securityJsCode: import.meta.env.VITE_AMAP_SECURITY || '' }
  const plugins = ['AMap.Geolocation']
  if (controls) plugins.push('AMap.Scale', 'AMap.ToolBar')
  const AMap = await AMapLoader.load({
    key,
    version: '2.0',
    plugins
  })
  const map = new AMap.Map(container, {
    zoom: 16,
    center: [centerLng, centerLat],
    viewMode: '2D',
    mapStyle
  })
  if (controls) {
    map.addControl(new AMap.Scale())
    map.addControl(new AMap.ToolBar({ position: 'RB' }))
  }
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
    marker.setContent(createPlaceMarkerElement(place, isActive, onMarkerClick))
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

  /**
   * @param {object} options
   * @param {boolean} options.fitView 是否自动缩放到全部地点
   * @param {boolean} options.preserveOtherOverlays 只移除本控制器创建的标记，
   *   不调用 clearMap()；首页地图由单一组件独占时使用，避免清掉非本组件覆盖物。
   */
  function render(places, { fitView = false, preserveOtherOverlays = false } = {}) {
    if (preserveOtherOverlays) {
      if (markers.length) map.remove(markers)
    } else {
      map.clearMap()
    }
    markers = []
    markerById.clear()
    placeById.clear()

    for (const place of places) {
      const isActive = activePlaceId === place.id
      const marker = new AMap.Marker({
        position: [Number(place.longitude), Number(place.latitude)],
        content: createPlaceMarkerElement(place, isActive, onMarkerClick),
        anchor: 'bottom-center',
        zIndex: isActive ? 200 : 100
      })
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

  /** 地点经纬度 → 地图容器内像素坐标，供视图锚定 UI（如手机端浮窗），不向外暴露 AMap 类型 */
  function projectToContainer(place) {
    const lng = Number(place?.longitude)
    const lat = Number(place?.latitude)
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null
    const pixel = map.lngLatToContainer([lng, lat])
    const x = typeof pixel?.getX === 'function' ? pixel.getX() : pixel?.x
    const y = typeof pixel?.getY === 'function' ? pixel.getY() : pixel?.y
    if (!Number.isFinite(x) || !Number.isFinite(y)) return null
    return { x, y }
  }

  /** 视口变化（拖动/缩放）时回调，返回解绑函数 */
  function onViewportChange(cb) {
    const handler = () => cb()
    map.on('mapmove', handler)
    map.on('zoomchange', handler)
    return () => {
      map.off('mapmove', handler)
      map.off('zoomchange', handler)
    }
  }

  return { render, focusPlace, setHighlight, setMyLocation, clearMyLocation, projectToContainer, onViewportChange }
}

/** @deprecated 请使用 createPlaceMarkerController */
export function renderMarkers(AMap, map, places, onMarkerClick) {
  const ctrl = createPlaceMarkerController(AMap, map, onMarkerClick)
  ctrl.render(places, { fitView: true })
  return ctrl
}

const ROUTE_COLORS = { WALK: '#2ba471', RIDE: '#1677ff', DRIVE: '#fa8c16', TRANSIT: '#722ed1' }

/**
 * 站间路线折线控制器：折线数据来自后端（与规划时刻同源缓存），前端只负责描绘。
 * segments=[{mode, polyline:[[lng,lat],...], dashed?}]；缺折线数据的段跳过不画；
 * dashed=true 用于“示意连线，非实际行走路线”。
 * 注意：createPlaceMarkerController.render 会 clearMap，折线应在标点渲染之后绘制。
 */
export function createRouteLineController(AMap, map) {
  let lines = []

  function draw(segments) {
    clear()
    for (const segment of segments || []) {
      const path = (segment.polyline || []).map(([lng, lat]) => [Number(lng), Number(lat)])
      if (path.length < 2) continue
      const line = new AMap.Polyline({
        path,
        strokeColor: segment.dashed ? '#909399' : (ROUTE_COLORS[segment.mode] || ROUTE_COLORS.WALK),
        strokeWeight: segment.dashed ? 4 : 6,
        strokeOpacity: segment.dashed ? 0.8 : 0.85,
        strokeStyle: segment.dashed ? 'dashed' : 'solid',
        showDir: !segment.dashed,
        lineJoin: 'round'
      })
      map.add(line)
      lines.push(line)
    }
  }

  function clear() {
    for (const line of lines) map.remove(line)
    lines = []
  }

  return { draw, clear }
}
