import test from 'node:test'
import assert from 'node:assert/strict'
import { safeRedirect } from '../src/utils/redirect.js'

test('站内路径原样返回', () => {
  assert.equal(safeRedirect('/maps/51?chat=1'), '/maps/51?chat=1')
  assert.equal(safeRedirect('/activities?activityId=35'), '/activities?activityId=35')
})

test('拒绝协议地址与协议相对地址', () => {
  assert.equal(safeRedirect('//evil.example'), '/')
  assert.equal(safeRedirect('https://evil.example'), '/')
  assert.equal(safeRedirect('javascript:alert(1)'), '/')
})

test('非字符串回首页', () => {
  assert.equal(safeRedirect(undefined), '/')
  assert.equal(safeRedirect(['/maps/1']), '/')
})
