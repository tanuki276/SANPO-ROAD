import { test } from 'node:test'
import assert from 'node:assert/strict'
import { GET } from '../api/share.js'
process.env.SUPABASE_URL = 'https://example.invalid'
process.env.SUPABASE_PUBLISHABLE_KEY = 'test'
test('all share API responses prohibit caching, including expired and revoked links', async () => {
  const original = globalThis.fetch
  try {
    const invalid = await GET(new Request('https://example.invalid/?token=invalid'))
    assert.equal(invalid.status, 404)
    assert.match(invalid.headers.get('cache-control'), /no-store/)
    const request = new Request('https://example.invalid/?token=' + 'a'.repeat(32))
    globalThis.fetch = async () => Response.json(null)
    const revoked = await GET(request)
    assert.equal(revoked.status, 404)
    assert.equal(revoked.headers.get('cdn-cache-control'), 'no-store')
    globalThis.fetch = async () => Response.json({name:'walk',show_map:false,points:[{lat:35,lng:139}]})
    const active = await GET(request)
    assert.equal(active.status, 200)
    assert.deepEqual((await active.json()).points, [])
    assert.equal(active.headers.get('vercel-cdn-cache-control'), 'no-store')
    globalThis.fetch = async () => new Response('',{status:500})
    const failed = await GET(request)
    assert.equal(failed.status,502)
    assert.match(failed.headers.get('cache-control'), /no-store/)
  } finally { globalThis.fetch = original }
})
