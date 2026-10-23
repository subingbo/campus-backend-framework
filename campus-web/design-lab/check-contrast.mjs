import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('../src/styles/tokens.css', import.meta.url), 'utf8')
const tokens = {}
for (const match of css.matchAll(/--([\w-]+):\s*(#[0-9a-fA-F]{6})/g)) {
  tokens[match[1].replace(/^ui-/, '')] = match[2]
}

function rgb(hex) {
  const value = hex.replace('#', '')
  return [0, 2, 4].map((i) => parseInt(value.slice(i, i + 2), 16) / 255)
}

function linear(channel) {
  return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
}

function luminance(hex) {
  const [r, g, b] = rgb(hex).map(linear)
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrast(a, b) {
  const l1 = luminance(a)
  const l2 = luminance(b)
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05)
}

const pairs = [
  ['ink / surface', 'ink', 'surface', 4.5],
  ['ink-2 / surface', 'ink-2', 'surface', 4.5],
  ['ink-3 / surface', 'ink-3', 'surface', 4.5],
  ['ink-3 / surface-2', 'ink-3', 'surface-2', 4.5],
  ['ink-2 / bg', 'ink-2', 'bg', 4.5],
  ['ink-3 / bg', 'ink-3', 'bg', 4.5],
  ['white / accent', null, 'accent', 4.5],
  ['accent / accent-tint', 'accent', 'accent-tint', 4.5],
  ['danger / danger-tint', 'danger', 'danger-tint', 4.5],
  ['warning / warning-tint', 'warning', 'warning-tint', 4.5],
  ['success / success-tint', 'success', 'success-tint', 4.5]
]

let failed = 0
for (const [label, fg, bg, threshold] of pairs) {
  const foreground = fg ? tokens[fg] : '#ffffff'
  const background = tokens[bg]
  if (!foreground || !background) {
    console.log(`SKIP ${label}: missing token`)
    failed += 1
    continue
  }
  const ratio = contrast(foreground, background)
  const pass = ratio >= threshold
  if (!pass) failed += 1
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${label.padEnd(26)} ${ratio.toFixed(2)}:1  (need ${threshold}:1)`)
}

console.log(`\n${failed === 0 ? 'All essential pairs pass WCAG AA.' : `${failed} pair(s) need adjustment.`}`)
process.exitCode = failed === 0 ? 0 : 1
