package io.github.amtedrema.bigbanktheory.strategy;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.github.amtedrema.bigbanktheory.mugloar.Ad;
import io.github.amtedrema.bigbanktheory.mugloar.GameState;
import io.github.amtedrema.bigbanktheory.mugloar.ShopItem;
import org.springframework.stereotype.Component;

/**
 * Decides the next move. Pure: no network, no randomness, same input gives the same advice.
 *
 * Rules, in order:
 * 1. Buy a healing potion when lives are low and gold allows.
 * 2. Buy the next level item while some ad on offer is not certain and gold covers the item plus the
 *    reserve.
 * 3. Otherwise solve the ad with the highest expected value: p * reward - (1 - p) * LIFE_VALUE, with
 *    one more LIFE_VALUE taken off a Steal ad.
 *
 * Ties on value: higher reward first, then smaller adId. Ads with an unknown label, and ads that are
 * still encrypted after decoding (their adId is unusable), are ranked after every known ad; an
 * encrypted one is never chosen.
 */
@Component
public class Strategy {

	private static final String UNKNOWN = "UNKNOWN";

	/**
	 * What a lost life costs, in reward points. Tuned, not derived: picked from measured income per lost
	 * life after the first value, 96, allowed an early death.
	 */
	static final double LIFE_VALUE = 454;

	private static final String HPOT = "hpot";

	/** Buy a healing potion at this many lives or fewer. A potion was only measured at 1 life, where it gave +1 life. */
	private static final int HPOT_AT_LIVES = 1;

	/** Gold kept back when buying level items, so a healing potion (50 gold) stays affordable. */
	private static final long RESERVE = 50;

	private record LevelItem(String id, int levels) {
	}

	/**
	 * Level items in buying order with the levels they give. The 100-gold items come first because they
	 * give more levels per gold. Why buy: ads of the same label paid about two to three times as much at
	 * level 15 as at level 0, with no visible effect on the success rate. An association, not proof.
	 */
	private static final List<LevelItem> LEVEL_LADDER = List.of(
			new LevelItem("cs", 1), new LevelItem("gas", 1), new LevelItem("wax", 1),
			new LevelItem("tricks", 1), new LevelItem("wingpot", 1),
			new LevelItem("ch", 2), new LevelItem("rf", 2), new LevelItem("iron", 2),
			new LevelItem("mtrix", 2), new LevelItem("wingpotmax", 2));

	/**
	 * Bought in turn once the ladder is done. Offers get harder with the turn and easier with the level:
	 * one turn takes about 1.14 levels to offset. A purchase costs one turn, so a 100-gold item (+1 level)
	 * falls behind, and these 300-gold items (+2 levels) gain ground.
	 */
	private static final List<String> REPEAT_ITEMS = List.of("ch", "rf", "iron", "mtrix", "wingpotmax");

	/** Messages that start like this were solved 0 of 29 times, so the chance is set to 0 at any reward. */
	private static final String TRAP_PREFIX = "Steal super awesome diamond";

	/**
	 * Each solved Steal ad moved the state reputation by -2 and no other ad type measured moved it. Games
	 * where many were solved early filled up with trap ads ("people who did not appreciate your dealings").
	 * Advice has no memory of past moves, so Steal ads are avoided altogether: one life value is taken off,
	 * which leaves them only as a way out when every other ad is worse.
	 */
	private static final String STEAL_PREFIX = "Steal ";

	/** Success rate per label: successes and attempts in real games, trap ads left out. A working estimate. */
	private static final Map<String, Double> RATES = Map.ofEntries(
			Map.entry("Piece of cake", rate(169, 176)),
			Map.entry("Sure thing", rate(27, 27)),
			Map.entry("Walk in the park", rate(86, 100)),
			Map.entry("Quite likely", rate(61, 80)),
			Map.entry("Hmmm....", rate(40, 63)),
			Map.entry("Gamble", rate(39, 67)),
			Map.entry("Rather detrimental", rate(27, 64)),
			Map.entry("Risky", rate(25, 63)),
			Map.entry("Playing with fire", rate(9, 63)),
			Map.entry("Suicide mission", rate(6, 113)),
			Map.entry("Impossible", rate(0, 127)));

	private static double rate(int successes, int attempts) {
		return (double) successes / attempts;
	}

	/** Best first: known labels before unknown, then value, then reward, then adId. */
	private static final Comparator<RatedAd> BEST_FIRST = Comparator
			.comparing((RatedAd r) -> r.label().equals(UNKNOWN))
			.thenComparing(RatedAd::expectedValue, Comparator.reverseOrder())
			.thenComparing((RatedAd r) -> r.ad().reward(), Comparator.reverseOrder())
			.thenComparing((RatedAd r) -> r.ad().adId());

	public Advice decide(GameState state, List<Ad> ads, List<ShopItem> shop) {
		List<RatedAd> rated = ads.stream().map(Strategy::rate).sorted(BEST_FIRST).toList();

		Optional<Advice> purchase = purchase(state, shop, rated);
		if (purchase.isPresent()) {
			return purchase.get();
		}
		Optional<RatedAd> best = rated.stream().filter(r -> r.ad().encrypted() == null).findFirst();
		if (best.isEmpty()) {
			return new Advice(rated, Move.none(), "No playable ad: the list is empty or no ad could be decoded.");
		}
		return new Advice(rated, Move.solve(best.get().ad().adId()),
				"Chosen as the best of the playable ads. " + best.get().reason());
	}

	private static Optional<Advice> purchase(GameState state, List<ShopItem> shop, List<RatedAd> rated) {
		Optional<ShopItem> hpot = find(shop, HPOT);
		if (state.lives() <= HPOT_AT_LIVES && hpot.isPresent() && state.gold() >= hpot.get().cost()) {
			return Optional.of(new Advice(rated, Move.buy(HPOT), "Lives are low (" + state.lives()
					+ "): buy a healing potion for " + hpot.get().cost() + " gold."));
		}
		if (allCertain(rated)) {
			return Optional.empty();
		}
		Optional<ShopItem> item = find(shop, nextLevelItem(state.level()));
		if (item.isPresent() && state.gold() >= item.get().cost() + RESERVE) {
			return Optional.of(new Advice(rated, Move.buy(item.get().id()), "Gold " + state.gold() + " covers "
					+ item.get().name() + " (" + item.get().cost() + ") and a " + RESERVE
					+ " gold reserve, and not every ad is certain: buy it for easier ads and higher rewards."));
		}
		return Optional.empty();
	}

	/**
	 * Stops the buying. A buy adds levels and a solve adds score, and every move takes a turn. Buying
	 * until the offers are certain keeps the game ahead of the difficulty with no fitted formula, and
	 * left 37% of the moves for solving in real games, near the 40 to 45% that a rough model finds best.
	 * An empty list is not certain.
	 */
	private static boolean allCertain(List<RatedAd> rated) {
		return !rated.isEmpty() && rated.stream().allMatch(r -> r.probability() >= 1);
	}

	/**
	 * The next ladder item for this level, or a repeat item past the ladder. A level between two steps
	 * (a person can buy off the ladder in the web app) continues at the next step.
	 */
	private static String nextLevelItem(int level) {
		int reached = 0;
		for (LevelItem step : LEVEL_LADDER) {
			if (reached >= level) {
				return step.id();
			}
			reached += step.levels();
		}
		return REPEAT_ITEMS.get((level - reached) / 2 % REPEAT_ITEMS.size());
	}

	private static Optional<ShopItem> find(List<ShopItem> shop, String id) {
		return shop.stream().filter(i -> i.id().equals(id)).findFirst();
	}

	private static RatedAd rate(Ad ad) {
		Double p = ad.encrypted() == null && ad.probability() != null ? RATES.get(ad.probability()) : null;
		if (p == null) {
			return new RatedAd(ad, UNKNOWN, 0, -LIFE_VALUE,
					"No measured chance for this ad (UNKNOWN), so it is ranked last.");
		}
		boolean trap = ad.message().startsWith(TRAP_PREFIX);
		if (trap) {
			p = 0.0;
		}
		boolean steal = !trap && ad.message().startsWith(STEAL_PREFIX);
		double value = p * ad.reward() - (1 - p) * LIFE_VALUE - (steal ? LIFE_VALUE : 0);
		String note = trap ? "Known trap ad (0 of 29 solved). "
				: steal ? "Steal ad: it brings trap ads later, so one more life value is taken off. " : "";
		return new RatedAd(ad, ad.probability(), p, value, String.format(Locale.ROOT,
				"%sExpected value: %.2f x %d reward minus %.2f x life value %.0f = %.1f.",
				note, p, ad.reward(), 1 - p, LIFE_VALUE, value));
	}
}
