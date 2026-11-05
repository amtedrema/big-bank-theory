import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'

import type { Advice } from '@/api'
import NextMove from '../NextMove.vue'
import { ad, adviceOf, playingStore, SHOP, spyOnActions } from './fixtures'

function mountWith(advice: Advice | null, game = {}, busy = false) {
  const { pinia, store } = playingStore(game)
  const actions = spyOnActions(store)
  store.shop = SHOP
  store.advice = advice
  store.busy = busy
  return { wrapper: mount(NextMove, { global: { plugins: [pinia] } }), actions }
}

const solveAdvice = (over = {}) =>
  adviceOf({ type: 'SOLVE', id: 'ad000002' }, [ad('ad000001'), ad('ad000002', over)], 'Best odds')
const buyAdvice = () => adviceOf({ type: 'BUY', id: 'hpot' }, [ad('ad000001')], 'Lives are low')

describe('NextMove', () => {
  it('describes the recommended ad with the reason', () => {
    const { wrapper } = mountWith(solveAdvice())
    expect(wrapper.text()).toContain('Solve: Message ad000002')
    expect(wrapper.text()).toContain('Best odds')
  })

  it('solves the recommended ad when its button is pressed', async () => {
    const { wrapper, actions } = mountWith(solveAdvice())
    const button = wrapper.find('button')
    expect(button.text()).toBe('Solve this ad')
    expect(button.attributes('aria-label')).toBe('Solve this ad: Message ad000002')
    await button.trigger('click')
    expect(actions.solve).toHaveBeenCalledTimes(1)
    expect(actions.solve).toHaveBeenCalledWith('ad000002')
    expect(actions.buy).not.toHaveBeenCalled()
  })

  it('describes the recommended purchase with the reason', () => {
    const { wrapper } = mountWith(buyAdvice(), { gold: 60 })
    expect(wrapper.text()).toContain('Buy: Healing potion for 50 gold')
    expect(wrapper.text()).toContain('Lives are low')
  })

  it('buys the recommended item when its button is pressed', async () => {
    const { wrapper, actions } = mountWith(buyAdvice(), { gold: 60 })
    const button = wrapper.find('button')
    expect(button.text()).toBe('Buy Healing potion')
    await button.trigger('click')
    expect(actions.buy).toHaveBeenCalledTimes(1)
    expect(actions.buy).toHaveBeenCalledWith('hpot')
    expect(actions.solve).not.toHaveBeenCalled()
  })

  it('buys with exactly the gold it costs', () => {
    const { wrapper } = mountWith(buyAdvice(), { gold: 50 })
    expect(wrapper.find('button').attributes('disabled')).toBeUndefined()
  })

  it('disables the purchase and says how much gold is missing', () => {
    const { wrapper } = mountWith(buyAdvice(), { gold: 20 })
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('You need 30 more gold')
  })

  it('disables the button while busy and when the game is over', () => {
    expect(
      mountWith(solveAdvice(), {}, true).wrapper.find('button').attributes('disabled'),
    ).toBeDefined()
    expect(
      mountWith(solveAdvice(), { lives: 0 }).wrapper.find('button').attributes('disabled'),
    ).toBeDefined()
    expect(
      mountWith(buyAdvice(), { gold: 500 }, true).wrapper.find('button').attributes('disabled'),
    ).toBeDefined()
  })

  it('disables the button for a recommended ad that could not be decoded', () => {
    const { wrapper } = mountWith(solveAdvice({ encrypted: 'c2VjcmV0' }))
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('cannot be solved')
  })

  it('says no move is recommended, with the reason and no button', () => {
    const { wrapper } = mountWith(adviceOf({ type: 'NONE', id: null }, [], 'Nothing playable'))
    expect(wrapper.text()).toContain('No move is recommended.')
    expect(wrapper.text()).toContain('Nothing playable')
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('shows nothing without advice', () => {
    const { wrapper } = mountWith(null)
    expect(wrapper.text()).toBe('')
  })
})
