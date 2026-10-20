const byId = id => document.getElementById(id)

const state = {
  loading: byId('loading'),
  content: byId('content'),
  error: byId('error'),
}

function showState(name) {
  state.loading.hidden = name !== 'loading'
  state.content.hidden = name !== 'content'
  state.error.hidden = name !== 'error'
}

function formatDistance(meters) {
  const value = Number(meters)
  const km = (Number.isFinite(value) ? Math.max(0, value) : 0) / 1000
  return `${km.toFixed(2)} km`
}

function formatDuration(ms) {
  const total = Math.max(0, Math.floor((Number(ms) || 0) / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  if (hours > 0) return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

function formatPace(meters, ms) {
  const distance = Number(meters)
  const duration = Number(ms)
  if (!Number.isFinite(distance) || !Number.isFinite(duration) || distance < 50 || duration <= 0) return '--'
  const minutesPerKm = (duration / 60000) / (distance / 1000)
  if (!Number.isFinite(minutesPerKm) || minutesPerKm <= 0 || minutesPerKm > 99) return '--'
  let minutes = Math.floor(minutesPerKm)
  let seconds = Math.round((minutesPerKm - minutes) * 60)
  if (seconds === 60) {
    minutes += 1
    seconds = 0
  }
  return `${minutes}:${String(seconds).padStart(2, '0')}/km`
}

function formatDate(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('ja-JP', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function validPoint(point) {
  const lat = Number(point?.lat)
  const lng = Number(point?.lng)
  return Number.isFinite(lat) && Number.isFinite(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180
}

function splitSegments(points) {
  const segments = []
  let activeSegment = null
  let active = null

  for (const point of points) {
    const segment = Number.isInteger(point.segment) ? point.segment : Number(point.segment) || 0
    if (active === null || segment !== activeSegment) {
      active = []
      activeSegment = segment
      segments.push(active)
    }
    active.push([Number(point.lat), Number(point.lng)])
  }

  return segments.filter(segment => segment.length > 0)
}

function renderMap(data) {
  const mapElement = byId('map')
  const routeOff = byId('routeOff')
  const mapHint = byId('mapHint')
  const privacyTitle = byId('privacyTitle')
  const privacyText = byId('privacyText')
  const points = Array.isArray(data.points) ? data.points.filter(validPoint) : []
  const showMap = data.showMap === true && points.length > 0

  if (!showMap) {
    mapElement.hidden = true
    routeOff.hidden = false
    mapHint.textContent = '位置情報は非公開'
    privacyTitle.textContent = '地図なしで共有'
    privacyText.textContent = 'この共有データには散歩ルートの座標が含まれていません。'
    return
  }

  if (typeof L === 'undefined') {
    mapElement.hidden = true
    routeOff.hidden = false
    mapHint.textContent = '地図を読み込めませんでした'
    privacyTitle.textContent = '地図つきで共有'
    privacyText.textContent = 'ルートは共有されていますが、現在地図を表示できません。'
    return
  }

  mapElement.hidden = false
  routeOff.hidden = true
  mapHint.textContent = 'ピンチ操作で拡大・縮小できます'
  privacyTitle.textContent = '地図つきで共有'
  privacyText.textContent = 'この共有ページには散歩ルートが含まれています。リンクの取り扱いに注意してください。'

  const map = L.map(mapElement, {
    scrollWheelZoom: false,
    zoomControl: true,
    attributionControl: true,
  })

  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '© OpenStreetMap contributors',
  }).addTo(map)

  const segments = splitSegments(points)
  const line = L.polyline(segments, {
    weight: 6,
    opacity: 0.92,
    color: '#256b40',
    lineCap: 'round',
    lineJoin: 'round',
  }).addTo(map)

  const start = [Number(points[0].lat), Number(points[0].lng)]
  const end = [Number(points[points.length - 1].lat), Number(points[points.length - 1].lng)]

  L.circleMarker(start, {
    radius: 7,
    color: '#124d2b',
    fillColor: '#ffffff',
    fillOpacity: 1,
    weight: 4,
  }).addTo(map).bindTooltip('表示区間の始点')

  if (points.length > 1) {
    L.circleMarker(end, {
      radius: 7,
      color: '#8d3f37',
      fillColor: '#ffffff',
      fillOpacity: 1,
      weight: 4,
    }).addTo(map).bindTooltip('表示区間の終点')
  }

  const bounds = line.getBounds()
  if (bounds.isValid() && !bounds.getNorthEast().equals(bounds.getSouthWest())) {
    map.fitBounds(bounds, { padding: [32, 32], maxZoom: 17 })
  } else {
    map.setView(start, 17)
  }

  requestAnimationFrame(() => map.invalidateSize({ pan: false }))
}

function render(data) {
  byId('name').textContent = typeof data.name === 'string' && data.name.trim() ? data.name.trim() : '散歩記録'
  byId('date').textContent = formatDate(data.startedAt)
  byId('distance').textContent = formatDistance(data.distanceMeters)
  byId('duration').textContent = formatDuration(data.durationMs)
  byId('pace').textContent = formatPace(data.distanceMeters, data.durationMs)

  const noteCard = byId('noteCard')
  const note = typeof data.note === 'string' ? data.note.trim() : ''
  if (note) {
    byId('note').textContent = note
    noteCard.hidden = false
  } else {
    noteCard.hidden = true
  }

  // Leaflet must measure a visible container. Reveal the page first, then build the map.
  showState('content')
  requestAnimationFrame(() => renderMap(data))
}

function tokenFromPath() {
  const parts = location.pathname.split('/').filter(Boolean)
  const token = parts.at(-1) || ''
  return /^[a-f0-9]{32}$/i.test(token) ? token : null
}

async function load() {
  showState('loading')
  const token = tokenFromPath()
  if (!token) {
    showState('error')
    return
  }

  try {
    const response = await fetch(`/api/share?token=${encodeURIComponent(token)}`, {
      method: 'GET',
      cache: 'no-store',
      credentials: 'omit',
      headers: { Accept: 'application/json' },
    })

    if (!response.ok) throw new Error('share_unavailable')
    const data = await response.json()
    if (!data || typeof data !== 'object') throw new Error('invalid_share')
    render(data)
  } catch {
    showState('error')
  }
}

load()

