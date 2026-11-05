// Types mirror the backend records (mugloar/ and strategy/). The only place that calls fetch.

export interface GameState {
  gameId: string
  lives: number
  gold: number
  level: number
  score: number
  turn: number
}

export interface Ad {
  adId: string
  message: string
  reward: number
  expiresIn: number
  probability: string | null
  /** Not null when the ad could not be decoded: its adId is unusable and it cannot be solved. */
  encrypted: string | null
}

export interface RatedAd {
  ad: Ad
  /** 'UNKNOWN' when the strategy has no estimate for this kind of ad; such an ad is still solvable. */
  label: string
  probability: number
  expectedValue: number
  reason: string
}

export interface Move {
  type: 'SOLVE' | 'BUY' | 'NONE'
  /** Ad id or shop item id; null for NONE. */
  id: string | null
}

export interface Advice {
  ads: RatedAd[]
  move: Move
  reason: string
}

export interface ShopItem {
  id: string
  name: string
  cost: number
}

/** No level: a solve answer does not carry it. */
export interface SolveResult {
  success: boolean
  lives: number
  gold: number
  score: number
  turn: number
  message: string
}

/** No score: a buy answer does not carry it. */
export interface BuyResult {
  shoppingSuccess: boolean
  gold: number
  lives: number
  level: number
  turn: number
}

const BACKEND_CODES = [
  'INVALID_INPUT',
  'AD_UNAVAILABLE',
  'GAME_NOT_FOUND',
  'GAME_OVER',
  'UPSTREAM_UNAVAILABLE',
  'UPSTREAM_INVALID',
  'INTERNAL_ERROR',
  'REQUEST_REJECTED',
] as const

/** The backend codes, plus NETWORK (no answer at all) and UNKNOWN (an answer we cannot read). */
export type ErrorCode = (typeof BACKEND_CODES)[number] | 'NETWORK' | 'UNKNOWN'

export class ApiError extends Error {
  code: ErrorCode

  constructor(code: ErrorCode, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/** An ad that can be sent to the solve endpoint. Not the same as label === 'UNKNOWN'. */
export function isSolvable(ad: Ad): boolean {
  return ad.encrypted == null
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

function errorFrom(text: string): ApiError {
  // Not every error body is ours: Tomcat and proxies send small HTML pages without a code.
  const body = parseJson(text)
  const problem = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {}
  const code = BACKEND_CODES.find((c) => c === problem.code) ?? 'UNKNOWN'
  const message =
    typeof problem.detail === 'string' ? problem.detail : 'The game could not be reached.'
  return new ApiError(code, message)
}

async function request<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
  let response: Response
  let text: string
  try {
    response = await fetch(
      path,
      body === undefined
        ? { method }
        : {
            method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
          },
    )
    text = await response.text()
  } catch {
    throw new ApiError('NETWORK', 'The server could not be reached')
  }
  if (!response.ok) {
    throw errorFrom(text)
  }
  const data = parseJson(text)
  if (data === undefined) {
    throw new ApiError('UNKNOWN', 'The server sent an unreadable answer')
  }
  return data as T
}

const game = (gameId: string) => `/api/games/${encodeURIComponent(gameId)}`

export const startGame = () => request<GameState>('POST', '/api/games')

export const getAdvice = (state: GameState) =>
  request<Advice>('POST', `${game(state.gameId)}/advice`, state)

export const solveAd = (gameId: string, adId: string) =>
  request<SolveResult>('POST', `${game(gameId)}/ads/${encodeURIComponent(adId)}/solve`)

export const getShop = (gameId: string) => request<ShopItem[]>('GET', `${game(gameId)}/shop`)

export const buyItem = (gameId: string, itemId: string) =>
  request<BuyResult>('POST', `${game(gameId)}/shop/${encodeURIComponent(itemId)}/buy`)
