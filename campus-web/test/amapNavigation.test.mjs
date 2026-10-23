import test from 'node:test'
import assert from 'node:assert/strict'
import * as navigation from '../src/utils/amapNavigation.js'

const from = { longitude: 113.3845, latitude: 23.0459, name: '东区图书馆' }
const to = { longitude: 113.3851, latitude: 23.0452, name: '南门咖啡 & 茶' }

test('navigation keeps both group map coordinates and the requested mode', () => {
  for (const [mode, expected] of [['WALK', 'walk'], ['DRIVE', 'car'], ['RIDE', 'ride'], ['TAXI', 'car'], ['TRANSIT', 'bus']]) {
    const url = new URL(navigation.buildAmapNavigationUrl({ from, to, mode }))
    assert.equal(url.pathname, '/navigation')
    assert.equal(url.searchParams.get('from'), '113.384500,23.045900,东区图书馆')
    assert.equal(url.searchParams.get('to'), '113.385100,23.045200,南门咖啡 & 茶')
    assert.equal(url.searchParams.get('mode'), expected)
    assert.equal(url.searchParams.get('callnative'), '1')
  }
})

test('invalid destinations never produce a navigation URL', () => {
  for (const target of [null, { lng: '', lat: 23 }, { lng: 'oops', lat: 23 }, { lng: 181, lat: 23 }, { lng: 113, lat: 91 }, { lng: Infinity, lat: 23 }]) {
    assert.equal(navigation.buildAmapNavigationUrl({ from, to: target }), '')
  }
})

test('missing or invalid origins use a valid marker instead of broken coordinates', () => {
  for (const start of [null, { lng: NaN, lat: 23 }]) {
    const url = new URL(navigation.buildAmapNavigationUrl({ from: start, to }))
    assert.equal(url.pathname, '/marker')
    assert.equal(url.searchParams.get('position'), '113.385100,23.045200')
  }
})

test('route events become named navigation links even without a draft or polyline', () => {
  const route = { from: [113.3845, 23.0459], to: [113.3851, 23.0452], mode: 'WALK', distanceMeters: 316, durationSeconds: 252, estimated: false, provider: 'amap' }
  const links = navigation.buildGeoRouteLinks([
    { type: 'PLACES', places: [from, to] },
    { type: 'ROUTE', route },
    { type: 'ROUTE', route },
    { type: 'ROUTE', route: { ...route, mode: 'DRIVE', estimated: true } },
    { type: 'ROUTE', route: { ...route, to: [null, 23] } }
  ])
  assert.equal(links.length, 2)
  assert.equal(links[0].fromName, '东区图书馆')
  assert.equal(links[0].toName, '南门咖啡 & 茶')
  assert.equal(links[0].modeLabel, '步行')
  assert.equal(links[0].minutes, 5)
  assert.equal(links[0].estimated, false)
  assert.equal(new URL(links[0].url).searchParams.get('mode'), 'walk')
  assert.equal(new URL(links[1].url).searchParams.get('mode'), 'car')
  assert.equal(links[1].estimated, true)
})
