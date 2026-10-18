export async function GET(request) {
  const url = new URL(request.url)
  const token = (url.searchParams.get('token') || '').trim()
  if (!/^[a-f0-9]{32}$/i.test(token)) {
    return Response.json({ error: 'not_found' }, { status: 404 })
  }

  const supabaseUrl = process.env.SUPABASE_URL
  const publishableKey = process.env.SUPABASE_PUBLISHABLE_KEY
  if (!supabaseUrl || !publishableKey) {
    console.error('Missing Supabase share environment variables')
    return Response.json({ error: 'server_config' }, { status: 500 })
  }

  try {
    const response = await fetch(`${supabaseUrl}/rest/v1/rpc/get_sanpo_share`, {
      method: 'POST',
      headers: {
        apikey: publishableKey,
        'content-type': 'application/json',
      },
      body: JSON.stringify({ share_token: token }),
      cache: 'no-store',
    })

    if (!response.ok) {
      console.error('Supabase share lookup failed', response.status)
      return Response.json({ error: 'upstream' }, { status: 502 })
    }

    const data = await response.json()
    if (!data) return Response.json({ error: 'not_found' }, { status: 404 })

    return Response.json({
      name: data.name,
      startedAt: data.started_at,
      durationMs: data.duration_ms,
      distanceMeters: data.distance_meters,
      points: data.show_map ? (data.points || []) : [],
      note: data.note || '',
      showMap: data.show_map !== false,
    }, {
      headers: {
        'cache-control': 'public, max-age=60, s-maxage=300',
        'x-robots-tag': 'noindex, nofollow, noarchive',
      },
    })
  } catch (error) {
    console.error('Share lookup error', error)
    return Response.json({ error: 'server_error' }, { status: 500 })
  }
}
