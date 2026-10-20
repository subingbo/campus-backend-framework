/** Trace 成本展示：未知成本绝不显示为 0；已知零成本正常显示 0。 */
export function formatCostAmount(value, currency = 'CNY') {
  if (value == null || value === '') return '不可估算'
  const number = Number(value)
  if (!Number.isFinite(number)) return '不可估算'
  const symbol = currency === 'CNY' ? '¥' : currency === 'USD' ? '$' : `${currency} `
  if (number === 0) return `${symbol}0.00`
  if (number > 0 && number < 0.0001) return `<${symbol}0.0001`
  return symbol + number.toFixed(number < 1 ? 4 : 2)
}

export function formatCost(row) {
  if (row?.costStatus !== 'KNOWN' || row.estimatedCost == null) return '不可估算'
  return formatCostAmount(row.estimatedCost, row.costCurrency)
}

export function costHint(row) {
  if (row?.costStatus === 'KNOWN') {
    const version = row.costPricingVersion || 'config'
    const label = version.startsWith('demo') ? '演示费率' : '费率'
    return `按 ${row.costCurrency || 'CNY'} · ${label} ${version} 估算`
  }
  return {
    PARTIAL: '存在丢失事件，成本不可估算',
    UNKNOWN_MODEL: '模型信息不完整（旧 Trace 或混合模型）',
    UNKNOWN_RATE: '费率未配置',
    UNKNOWN_TOKENS: 'Token 未完整返回',
    DISABLED: '成本核算已关闭'
  }[row?.costStatus] || '不可估算'
}
