import { computed, ref } from 'vue'

/** 定位错误码 → 文案。不依赖任何地图厂商，手机端接入时可直接复用 */
const LOCATION_ERROR_MESSAGES = {
  PERMISSION_DENIED: '定位权限被拒绝，请在浏览器或系统设置中允许获取位置后重试',
  TIMEOUT: '定位超时，请确认定位开关已开启，并到窗边或室外重试',
  POSITION_UNAVAILABLE: '暂时无法获取当前位置，请检查定位开关与网络',
  NOT_SUPPORTED: '当前环境不支持定位',
  PROVIDER_UNAVAILABLE: '定位能力未就绪，请刷新页面重试',
  UNKNOWN: '定位失败，请稍后重试'
}

/** 网络（IP）定位或误差过大时不应当作精确位置使用 */
export function isCoarseLocation(location) {
  if (!location) return false
  if (location.locationType === 'ip') return true
  const accuracy = Number(location.accuracy)
  return Number.isFinite(accuracy) && accuracy > 200
}

export function describeLocation(location) {
  if (!location) return ''
  const accuracy = Math.round(Number(location.accuracy) || 0)
  if (!accuracy) return '精度未知'
  if (location.locationType === 'ip') return `约 ${accuracy} 米（网络定位）`
  return `约 ${accuracy} 米`
}

function toErrorMessage(error) {
  const code = error?.code || 'UNKNOWN'
  const base = LOCATION_ERROR_MESSAGES[code] || error?.message || LOCATION_ERROR_MESSAGES.UNKNOWN
  return error?.hint ? `${base}（${error.hint}）` : base
}

/**
 * 定位能力层：只负责「拿到当前位置」，不认识地图，也不弹界面提示（提示交给视图）。
 * @param {() => (object | null)} getProvider 返回定位提供者；Web 端是高德适配器，手机端可换成系统定位
 *   provider 需实现 getCurrentPosition(): Promise<{ lng: number, lat: number, accuracy: number, locationType: string }>，
 *   失败时 reject 一个带 code 的错误（可选 hint 承载平台特有说明）
 */
export function useLocation(getProvider) {
  const locating = ref(false)
  const location = ref(null)
  const error = ref('')

  const summary = computed(() => describeLocation(location.value))
  const isCoarse = computed(() => isCoarseLocation(location.value))

  async function locate() {
    if (locating.value) return null
    error.value = ''
    const provider = getProvider ? getProvider() : null
    if (!provider) {
      error.value = toErrorMessage({ code: 'PROVIDER_UNAVAILABLE' })
      return null
    }
    locating.value = true
    try {
      const result = await provider.getCurrentPosition()
      location.value = result
      return result
    } catch (e) {
      error.value = toErrorMessage(e)
      return null
    } finally {
      locating.value = false
    }
  }

  function reset() {
    location.value = null
    error.value = ''
  }

  return { locating, location, error, summary, isCoarse, locate, reset }
}
