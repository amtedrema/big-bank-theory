import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'

import type { LogEntry } from '@/stores/game'
import BotPanel from '../BotPanel.vue'
import { playingStore, SHOP, spyOnActions } from './fixtures'

function mountWith(
  state: { autoplay?: boolean; busy?: boolean; log?: LogEntry[] } = {},
  game = {},
) {
  const { pinia, store } = playingStore(game)
  const actions = spyOnActions(store)
  store.shop = SHOP
  store.autoplay = state.autoplay ?? false
  store.busy = state.busy ?? false
  store.log = state.log ?? []
  return { wrapper: mount(BotPanel, { global: { plugins: [pinia] } }), actions, store }
}

const entry = (turn: number, over: Partial<LogEntry> = {}): LogEntry => ({
  turn,
  move: 'SOLVE',
  id: 'ad000001',
  message: 'Message ad000001',
  reason: `Reason ${turn}`,
  result: `Success: Result ${turn}`,
  ...over,
})

const toggle = (wrapper: ReturnType<typeof mountWith>['wrapper']) => wrapper.find('button')
const isDisabled = (wrapper: ReturnType<typeof mountWith>['wrapper']) =>
  toggle(wrapper).attributes('disabled') !== undefined
// While busy the button is only aria-disabled: a disabled button would drop keyboard focus.
const isBlocked = (wrapper: ReturnType<typeof mountWith>['wrapper']) =>
  isDisabled(wrapper) || toggle(wrapper).attributes('aria-disabled') === 'true'

describe('BotPanel toggle', () => {
  it('starts auto-play when it is stopped, and the label then says Stop', async () => {
    const { wrapper, actions, store } = mountWith()
    actions.startAutoplay.mockImplementation(async () => {
      store.autoplay = true
    })
    expect(toggle(wrapper).text()).toBe('Auto-play')
    expect(wrapper.find('[role="status"]').text()).toBe('Stopped')
    await toggle(wrapper).trigger('click')
    expect(actions.startAutoplay).toHaveBeenCalledTimes(1)
    expect(actions.stopAutoplay).not.toHaveBeenCalled()
    expect(toggle(wrapper).text()).toBe('Stop')
    expect(wrapper.find('[role="status"]').text()).toBe('Auto-play running')
  })

  it('stops auto-play when it is running', async () => {
    const { wrapper, actions } = mountWith({ autoplay: true })
    expect(toggle(wrapper).text()).toBe('Stop')
    await toggle(wrapper).trigger('click')
    expect(actions.stopAutoplay).toHaveBeenCalledTimes(1)
    expect(actions.startAutoplay).not.toHaveBeenCalled()
  })

  it('does not start auto-play while a call runs', async () => {
    const { wrapper, actions } = mountWith({ busy: true })
    expect(isBlocked(wrapper)).toBe(true)
    expect(isDisabled(wrapper)).toBe(false)
    await toggle(wrapper).trigger('click')
    expect(actions.startAutoplay).not.toHaveBeenCalled()
  })

  it('keeps Stop enabled while a call runs, because auto-play keeps the store busy', async () => {
    const { wrapper, actions } = mountWith({ autoplay: true, busy: true })
    expect(toggle(wrapper).text()).toBe('Stop')
    expect(isBlocked(wrapper)).toBe(false)
    await toggle(wrapper).trigger('click')
    expect(actions.stopAutoplay).toHaveBeenCalledTimes(1)
  })

  it('is aria-disabled, not disabled, when the game is over, and does nothing', async () => {
    const { wrapper, actions } = mountWith({}, { lives: 0 })
    expect(isDisabled(wrapper)).toBe(false)
    expect(toggle(wrapper).attributes('aria-disabled')).toBe('true')
    await toggle(wrapper).trigger('click')
    expect(actions.startAutoplay).not.toHaveBeenCalled()
    expect(wrapper.find('[role="status"]').text()).toBe('Game over')
  })
})

describe('BotPanel log', () => {
  it('says so when there are no moves yet', () => {
    const { wrapper } = mountWith()
    expect(wrapper.text()).toContain('No moves yet')
    expect(wrapper.find('li').exists()).toBe(false)
  })

  it('shows the newest entry first, with turn, move, reason and result', () => {
    const { wrapper } = mountWith({ log: [entry(1), entry(2), entry(3)] })
    const items = wrapper.findAll('li')
    expect(items.map((li) => li.find('.message').text())).toEqual([
      'Turn 3: Solve',
      'Turn 2: Solve',
      'Turn 1: Solve',
    ])
    expect(items[0]?.text()).toContain('Message ad000001')
    expect(items[0]?.text()).toContain('Reason 3')
    expect(items[0]?.text()).toContain('Success: Result 3')
  })

  it('shows no ad text for a purchase', () => {
    const { wrapper } = mountWith({
      log: [{ turn: 1, move: 'BUY', id: 'hpot', reason: 'r', result: 'Bought it' }],
    })
    expect(wrapper.find('li').findAll('p')).toHaveLength(3)
  })

  it('shows a purchase by the name of the item, not its id', () => {
    const { wrapper } = mountWith({
      log: [entry(4, { move: 'BUY', id: 'hpot', result: 'Bought Healing potion' })],
    })
    expect(wrapper.find('li').text()).toContain('Turn 4: Buy Healing potion')
    expect(wrapper.find('li').text()).not.toContain('hpot')
  })

  it('shows a move that was not done or whose result is unknown, and a stop without a move', () => {
    const { wrapper } = mountWith({
      log: [
        entry(5, { result: 'Not done: The ad has expired' }),
        entry(6, { move: 'BUY', id: 'zzz', result: 'Result unknown: No connection' }),
        entry(7, { move: 'NONE', id: null, result: 'No playable move' }),
      ],
    })
    const texts = wrapper.findAll('li').map((li) => li.text())
    expect(texts[0]).toContain('Turn 7: No move')
    expect(texts[0]).toContain('No playable move')
    expect(texts[1]).toContain('Turn 6: Buy zzz')
    expect(texts[1]).toContain('Result unknown: No connection')
    expect(texts[2]).toContain('Not done: The ad has expired')
  })

  it('is a labelled region that the keyboard can reach, and is not a live region', () => {
    const { wrapper } = mountWith({ log: [entry(1)] })
    const log = wrapper.find('[role="region"]')
    const heading = wrapper.find('h3')
    expect(heading.text()).toBe('Decision log')
    expect(log.attributes('aria-labelledby')).toBe(heading.attributes('id'))
    expect(log.attributes('aria-label')).toBeUndefined()
    expect(log.attributes('tabindex')).toBe('0')
    expect(wrapper.find('[aria-live]').exists()).toBe(false)
    expect(wrapper.findAll('[role="status"]')).toHaveLength(1)
  })
})
