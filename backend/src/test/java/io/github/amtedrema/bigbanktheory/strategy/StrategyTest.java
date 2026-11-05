package io.github.amtedrema.bigbanktheory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import io.github.amtedrema.bigbanktheory.mugloar.Ad;
import io.github.amtedrema.bigbanktheory.mugloar.GameState;
import io.github.amtedrema.bigbanktheory.mugloar.ShopItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StrategyTest {

	private static final List<ShopItem> SHOP = List.of(
			new ShopItem("hpot", "Healing potion", 50),
			new ShopItem("cs", "Claw Sharpening", 100), new ShopItem("gas", "Gasoline", 100),
			new ShopItem("wax", "Copper Plating", 100), new ShopItem("tricks", "Book of Tricks", 100),
			new ShopItem("wingpot", "Potion of Stronger Wings", 100),
			new ShopItem("ch", "Claw Honing", 300), new ShopItem("rf", "Rocket Fuel", 300),
			new ShopItem("iron", "Iron Plating", 300), new ShopItem("mtrix", "Dragon Mtrix", 300),
			new ShopItem("wingpotmax", "Potion of Stronger Wings Max", 300));

	private final Strategy strategy = new Strategy();

	private static Ad ad(String id, String label, long reward) {
		return new Ad(id, "message " + id, reward, 5, label, null);
	}

	private static GameState state(int lives, long gold, int level) {
		return new GameState("g1", lives, gold, level, 0, 10);
	}

	private Advice decide(GameState state, Ad... ads) {
		return strategy.decide(state, List.of(ads), SHOP);
	}

	private static GameState plain() {
		return state(3, 0, 0);
	}

	// Choosing an ad

	@Test
	void higherRewardWinsWithinOneLabel() {
		Advice advice = decide(plain(), ad("low", "Walk in the park", 50), ad("high", "Walk in the park", 120));

		assertThat(advice.move()).isEqualTo(Move.solve("high"));
	}

	@Test
	void highRewardWithLowerChanceBeatsModestRewardWhenValueIsHigher() {
		// Gamble 600: 0.58209 x 600 - 0.41791 x 454 = 159.5. Piece of cake 100: 0.96023 x 100 - 0.03977 x 454 = 77.97.
		Advice advice = decide(plain(), ad("cake", "Piece of cake", 100), ad("gamble", "Gamble", 600));

		assertThat(advice.move()).isEqualTo(Move.solve("gamble"));
	}

	@Test
	void aGambleLosesToASmallSafeAdAtTheTunedLifeValue() {
		// At the life value 454 the cake wins (10.75 against -83.2). At the first value, 96, the Gamble would win.
		Advice advice = decide(plain(), ad("gamble", "Gamble", 183), ad("cake", "Piece of cake", 30));

		assertThat(advice.move()).isEqualTo(Move.solve("cake"));
	}

	// Trap ads

	private static Ad trap(String id, String label, long reward) {
		return new Ad(id, "Steal super awesome diamond potatoes from Jane Doe", reward, 5, label, null);
	}

	@Test
	void trapAdAtHighRewardLosesToModestAds() {
		Advice advice = decide(plain(), trap("trap", "Piece of cake", 428), ad("real", "Piece of cake", 250),
				ad("walk", "Walk in the park", 180), ad("sure", "Sure thing", 114));

		assertThat(advice.move()).isEqualTo(Move.solve("real"));
		RatedAd rated = advice.ads().stream().filter(r -> r.ad().adId().equals("trap")).findFirst().orElseThrow();
		assertThat(rated.probability()).isZero();
		assertThat(rated.expectedValue()).isEqualTo(-Strategy.LIFE_VALUE);
		assertThat(rated.reason()).contains("trap");
		assertThat(advice.ads().get(3)).isSameAs(rated);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"Steal super awesome diamond potatoes from Jane Doe|true",
			"Steal super awesome diamon|false",
			"steal super awesome diamond potatoes|false",
			"Please Steal super awesome diamond potatoes|false",
			"Steal beer mug delivery from Jane Doe|false"})
	void trapIsDetectedByThePrefixOnly(String message, boolean isTrap) {
		Ad candidate = new Ad("a", message, 300, 5, "Piece of cake", null);

		RatedAd rated = strategy.decide(plain(), List.of(candidate), SHOP).ads().get(0);

		assertThat(rated.probability()).isEqualTo(isTrap ? 0.0 : 169.0 / 176);
	}

	@ParameterizedTest
	@CsvSource({"Piece of cake, 20", "Piece of cake, 478", "Sure thing, 300"})
	void trapIsZeroAtEveryRewardAndLabel(String label, long reward) {
		RatedAd rated = decide(plain(), trap("trap", label, reward)).ads().get(0);

		assertThat(rated.probability()).isZero();
		assertThat(rated.expectedValue()).isEqualTo(-Strategy.LIFE_VALUE);
	}

	@ParameterizedTest
	@CsvSource({
			"Piece of cake, 0.96", "Sure thing, 1.00", "Walk in the park, 0.86", "Quite likely, 0.76",
			"Hmmm...., 0.63", "Gamble, 0.58", "Rather detrimental, 0.42", "Risky, 0.40",
			"Playing with fire, 0.14", "Suicide mission, 0.05", "Impossible, 0.00"})
	void everyLabelHasItsRateAndTheExpectedValueFormula(String label, double p) {
		for (long reward : new long[] {10, 400}) {
			RatedAd rated = decide(plain(), ad("a", label, reward)).ads().get(0);

			assertThat(rated.probability()).isCloseTo(p, within(0.005));
			assertThat(rated.expectedValue()).isCloseTo(rated.probability() * reward - (1 - rated.probability()) * Strategy.LIFE_VALUE, within(1e-9));
		}
	}

	@Test
	void trapAndImpossibleTieOnValueSoRewardThenAdIdDecides() {
		Advice byReward = decide(plain(), ad("a", "Impossible", 100), trap("b", "Piece of cake", 200));
		Advice byAdId = decide(plain(), trap("b", "Piece of cake", 100), ad("a", "Impossible", 100));

		assertThat(byReward.ads()).extracting(RatedAd::expectedValue).containsExactly(-Strategy.LIFE_VALUE, -Strategy.LIFE_VALUE);
		assertThat(byReward.ads()).extracting(r -> r.ad().adId()).containsExactly("b", "a");
		assertThat(byAdId.ads()).extracting(r -> r.ad().adId()).containsExactly("a", "b");
	}

	@Test
	void sameAdsInAnyOrderGiveTheSameAdvice() {
		List<Ad> ads = new ArrayList<>(List.of(
				ad("a", "Gamble", 120), ad("b", "Risky", 200), ad("c", "Piece of cake", 40),
				ad("d", "Impossible", 300), ad("e", "Quite likely", 90), ad("f", "Mystery", 70)));
		Advice expected = strategy.decide(plain(), ads, SHOP);

		for (int seed = 0; seed < 10; seed++) {
			Collections.shuffle(ads, new Random(seed));
			assertThat(strategy.decide(plain(), ads, SHOP)).isEqualTo(expected);
		}
	}

	@Test
	void exactTieGoesToTheSmallerAdId() {
		Advice advice = decide(plain(), ad("b", "Gamble", 100), ad("a", "Gamble", 100));

		assertThat(advice.move()).isEqualTo(Move.solve("a"));
	}

	// Unknown labels and undecoded ads

	@Test
	void unknownLabelIsRankedLastAndShownAsUnknown() {
		Advice advice = decide(plain(), ad("x", "Mystery", 500), ad("i", "Impossible", 10), ad("n", null, 400));

		assertThat(advice.ads()).extracting(r -> r.ad().adId()).containsExactly("i", "x", "n");
		assertThat(advice.ads().get(2).label()).isEqualTo("UNKNOWN");
		assertThat(advice.ads().get(1).label()).isEqualTo("UNKNOWN");
		assertThat(advice.move()).isEqualTo(Move.solve("i"));
	}

	@Test
	void unknownLabelIsOnlyChosenWhenNothingElseIsPlayable() {
		Advice advice = decide(plain(), ad("x", "Mystery", 500));

		assertThat(advice.move()).isEqualTo(Move.solve("x"));
	}

	@Test
	void adStillEncryptedIsRankedLastAndNeverChosen() {
		Ad undecoded = new Ad("zzzz", "garbled", 500, 5, "Piece of cake", "3");

		Advice only = decide(plain(), undecoded);
		Advice withOther = decide(plain(), undecoded, ad("i", "Impossible", 10));

		assertThat(only.move()).isEqualTo(Move.none());
		assertThat(withOther.move()).isEqualTo(Move.solve("i"));
		assertThat(withOther.ads().get(1).label()).isEqualTo("UNKNOWN");
	}

	@Test
	void emptyAdListGivesNoMoveWithReason() {
		Advice advice = strategy.decide(plain(), List.of(), SHOP);

		assertThat(advice.move()).isEqualTo(Move.none());
		assertThat(advice.reason()).isNotBlank();
	}

	@Test
	void everyAdviceHasAReasonAndEveryRatedAdToo() {
		Advice advice = decide(plain(), ad("a", "Gamble", 120), ad("b", "Mystery", 10));

		assertThat(advice.reason()).startsWith("Chosen as the best of the playable ads");
		assertThat(advice.ads().get(0).reason()).startsWith("Expected value: 0.58 x 120 reward minus 0.42 x life value " + (int) Strategy.LIFE_VALUE);
		assertThat(advice.ads()).allSatisfy(r -> assertThat(r.reason()).isNotBlank());
	}

	// Buying

	@ParameterizedTest
	@CsvSource({"1, 49, false", "1, 50, true", "2, 50, false", "3, 50, false"})
	void healingPotionOnlyWhenLivesAreLowAndGoldAllows(int lives, long gold, boolean buys) {
		Advice advice = decide(state(lives, gold, 6), ad("a", "Gamble", 100));

		assertThat(advice.move().equals(Move.buy("hpot"))).isEqualTo(buys);
		if (!buys) {
			assertThat(advice.move()).isEqualTo(Move.solve("a"));
		}
	}

	@Test
	void healingPotionComesBeforeLevelItemAndBeforeAnySolve() {
		Advice advice = decide(state(1, 500, 0), ad("a", "Piece of cake", 100));

		assertThat(advice.move()).isEqualTo(Move.buy("hpot"));
	}

	@ParameterizedTest
	@CsvSource({
			"0, 149, ''", "0, 150, cs", "1, 150, gas", "4, 150, wingpot",
			"5, 349, ''", "5, 350, ch", "7, 350, rf", "13, 350, wingpotmax",
			"6, 349, ''", "6, 350, rf"})
	void levelItemFollowsTheLadderWhenGoldCoversCostAndReserve(int level, long gold, String bought) {
		Advice advice = decide(state(3, gold, level), ad("a", "Gamble", 100));

		Move expected = bought.isEmpty() ? Move.solve("a") : Move.buy(bought);
		assertThat(advice.move()).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource({
			"15, 349, ''", "15, 350, ch", "17, 350, rf", "19, 350, iron", "21, 350, mtrix", "23, 350, wingpotmax",
			"25, 350, ch", "16, 350, ch", "413, 350, wingpotmax"})
	void pastTheLadderThe300GoldItemsRepeatWhenGoldCoversCostAndReserve(int level, long gold, String bought) {
		GameState levelEqualToTurn = new GameState("g1", 3, gold, level, 0, level);

		Advice advice = decide(levelEqualToTurn, ad("a", "Gamble", 100));

		Move expected = bought.isEmpty() ? Move.solve("a") : Move.buy(bought);
		assertThat(advice.move()).isEqualTo(expected);
	}

	@Test
	void whenEveryAdIsCertainTheBestIsSolvedHoweverMuchGoldThereIs() {
		Advice advice = decide(state(3, 1_000_000, 900), ad("low", "Sure thing", 5000), ad("high", "Sure thing", 7000));

		assertThat(advice.move()).isEqualTo(Move.solve("high"));
	}

	@ParameterizedTest
	@CsvSource({"Piece of cake", "Gamble", "Mystery"})
	void oneAdThatIsNotCertainMeansBuyingContinues(String label) {
		Advice advice = decide(state(3, 1_000_000, 900), ad("a", "Sure thing", 7000), ad("b", label, 5000));

		assertThat(advice.move().type()).isEqualTo(Move.Type.BUY);
	}

	@Test
	void aSureThingThatIsATrapIsNotCertain() {
		Advice advice = decide(state(3, 1_000_000, 900), ad("a", "Sure thing", 7000), trap("t", "Sure thing", 9000));

		assertThat(advice.move().type()).isEqualTo(Move.Type.BUY);
	}

	@Test
	void aPotionComesFirstEvenWhenEveryAdIsCertain() {
		Advice advice = decide(state(1, 500, 900), ad("a", "Sure thing", 7000));

		assertThat(advice.move()).isEqualTo(Move.buy("hpot"));
	}

	@Test
	void withoutGoldForALevelItemTheBestAdIsSolvedEvenIfItIsNotCertain() {
		Advice advice = decide(state(3, 349, 900), ad("a", "Gamble", 7000));

		assertThat(advice.move()).isEqualTo(Move.solve("a"));
	}

	// Steal ads

	private static Ad steal(String id, String label, long reward) {
		return new Ad(id, "Steal horse from Jane Doe", reward, 5, label, null);
	}

	@Test
	void aSteadyAdBeatsAStealAdWithAMuchHigherReward() {
		Advice advice = decide(plain(), steal("steal", "Sure thing", 300), ad("help", "Walk in the park", 40));

		assertThat(advice.move()).isEqualTo(Move.solve("help"));
		RatedAd rated = advice.ads().stream().filter(r -> r.ad().adId().equals("steal")).findFirst().orElseThrow();
		assertThat(rated.probability()).isEqualTo(1.0);
		assertThat(rated.expectedValue()).isEqualTo(300 - Strategy.LIFE_VALUE);
		assertThat(rated.reason()).contains("Steal ad");
	}

	@Test
	void aStealAdIsStillBetterThanAlmostCertainFailure() {
		Advice advice = decide(plain(), steal("steal", "Sure thing", 300), ad("hard", "Suicide mission", 400));

		assertThat(advice.move()).isEqualTo(Move.solve("steal"));
	}


	@Test
	void missingShopItemMeansNoPurchase() {
		Advice advice = strategy.decide(state(1, 500, 0), List.of(ad("a", "Gamble", 100)), List.of());

		assertThat(advice.move()).isEqualTo(Move.solve("a"));
	}

	@Test
	void purchaseIsAdvisedEvenWithoutAds() {
		Advice advice = strategy.decide(state(1, 60, 0), List.of(), SHOP);

		assertThat(advice.move()).isEqualTo(Move.buy("hpot"));
	}
}
