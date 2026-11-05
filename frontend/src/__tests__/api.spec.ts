import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError, buyItem, getAdvice, getShop, solveAd, startGame } from '../api'
import type { GameState } from '../api'

const state: GameState = { gameId: 'abcd1234', lives: 3, gold: 0, level: 0, score: 0, turn: 0 }

/** Each call gets a fresh Response, because a body can be read only once. */
function stubFetch(make: () => Response | Error) {
  const fetchMock = vi.fn<typeof fetch>(async () => {
    const response = make()
    if (response instanceof Error) {
      throw response
    }
    return response
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const problem = (status: number, code: string) =>
  new Response(JSON.stringify({ status, detail: 'Some text', code }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  const e = await promise.then(
    () => null,
    (error: unknown) => error,
  )
  expect(e).toBeInstanceOf(ApiError)
  return e as ApiError
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('request errors', () => {
  it('takes the code from a problem body', async () => {
    stubFetch(() => problem(409, 'AD_UNAVAILABLE'))
    const e = await failure(solveAd('abcd1234', 'ad123456'))
    expect(e.code).toBe('AD_UNAVAILABLE')
    expect(e.message).toBe('Some text')
  })

  it.each([
    ['INVALID_INPUT', 400],
    ['GAME_NOT_FOUND', 404],
    ['GAME_OVER', 410],
    ['UPSTREAM_INVALID', 502],
    ['UPSTREAM_UNAVAILABLE', 503],
    ['INTERNAL_ERROR', 500],
    ['REQUEST_REJECTED', 405],
  ])('keeps the backend code %s', async (code, status) => {
    stubFetch(() => problem(status, code))
    expect((await failure(getShop('abcd1234'))).code).toBe(code)
  })

  it('maps an HTML error page to UNKNOWN without a parse error', async () => {
    stubFetch(() => new Response('<html><body>Bad Gateway</body></html>', { status: 502 }))
    const e = await failure(startGame())
    expect(e.code).toBe('UNKNOWN')
    expect(e.message).not.toContain('<html>')
  })

  it('maps a code the client does not know to UNKNOWN', async () => {
    stubFetch(() => problem(500, 'SOMETHING_NEW'))
    expect((await failure(startGame())).code).toBe('UNKNOWN')
  })

  it('maps a JSON error body that is not an object to UNKNOWN', async () => {
    stubFetch(() => new Response('null', { status: 500 }))
    expect((await failure(startGame())).code).toBe('UNKNOWN')
  })

  it('maps a rejected fetch to NETWORK', async () => {
    stubFetch(() => new TypeError('Failed to fetch'))
    expect((await failure(startGame())).code).toBe('NETWORK')
  })

  it('maps a 200 answer that is not JSON to UNKNOWN', async () => {
    stubFetch(() => new Response('<html></html>', { status: 200 }))
    expect((await failure(startGame())).code).toBe('UNKNOWN')
  })
})

describe('calls', () => {
  it('posts the state to the advice route', async () => {
    const fetchMock = stubFetch(
      () => new Response('{"ads":[],"move":{"type":"NONE","id":null},"reason":""}'),
    )
    await getAdvice(state)
    const [path, init] = fetchMock.mock.calls[0]!
    expect(path).toBe('/api/games/abcd1234/advice')
    expect(init?.method).toBe('POST')
    expect(init?.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(JSON.parse(init?.body as string)).toEqual(state)
  })

  it('uses the backend routes for solve, shop and buy', async () => {
    const fetchMock = stubFetch(() => new Response('{}'))
    await solveAd('abcd1234', 'ad123456')
    await getShop('abcd1234')
    await buyItem('abcd1234', 'hpot')
    expect(fetchMock.mock.calls.map(([path, init]) => `${init?.method} ${path}`)).toEqual([
      'POST /api/games/abcd1234/ads/ad123456/solve',
      'GET /api/games/abcd1234/shop',
      'POST /api/games/abcd1234/shop/hpot/buy',
    ])
  })
})

describe('request details', () => {
  it('uses the default message when detail is not text', async () => {
    stubFetch(() => new Response('{"code":"INTERNAL_ERROR","detail":42}', { status: 500 }))
    expect((await failure(startGame())).message).toBe('The game could not be reached.')
  })

  it('encodes the ids in every path', async () => {
    const fetchMock = stubFetch(() => new Response('{}'))
    await solveAd('a/b c', 'x?y#z')
    await buyItem('a/b c', 'i/j')
    await getAdvice({ ...state, gameId: 'a/b c' })
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/games/a%2Fb%20c/ads/x%3Fy%23z/solve',
      '/api/games/a%2Fb%20c/shop/i%2Fj/buy',
      '/api/games/a%2Fb%20c/advice',
    ])
  })
})
