package io.github.amtedrema.bigbanktheory.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;

import io.github.amtedrema.bigbanktheory.bot.GameResult.Status;
import io.github.amtedrema.bigbanktheory.mugloar.MugloarException.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Command line entry point, active with the profile "bot". Options: --games=N (default 1) and
 * --target-score=N (default none). Plays the games one after another and logs a summary.
 */
@Component
@Profile("bot")
public class BotRunner implements ApplicationRunner {

	/** Without a target a game runs until its lives run out, and a game ahead of the difficulty may never get there. */
	static final long NO_TARGET = Long.MAX_VALUE;

	/** Games in a row aborted by an unavailable server (429, 5xx, network) before the run stops hammering it. */
	private static final int MAX_UPSTREAM_FAILURES_IN_A_ROW = 3;

	private static final String UPSTREAM_NOTE = Kind.UPSTREAM_UNAVAILABLE.name();

	private static final Logger log = LoggerFactory.getLogger(BotRunner.class);

	private final BotPlayer player;

	public BotRunner(BotPlayer player) {
		this.player = player;
	}

	@Override
	public void run(ApplicationArguments args) {
		int games = Math.toIntExact(option(args, "games", 1));
		long targetScore = option(args, "target-score", NO_TARGET);
		List<GameResult> results = new ArrayList<>();
		int upstreamFailures = 0;
		for (int i = 1; i <= games; i++) {
			GameResult result = player.play(targetScore);
			results.add(result);
			log.info("Game {}/{}: {} {} score {} turns {} {}", i, games, result.gameId(), result.status(),
					result.score(), result.turns(), result.note());
			upstreamFailures = isUpstreamFailure(result) ? upstreamFailures + 1 : 0;
			if (upstreamFailures >= MAX_UPSTREAM_FAILURES_IN_A_ROW && i < games) {
				log.error("RUN STOPPED after {} of {} games: the last {} games aborted with {}. The server may be"
						+ " rate limiting (429) or down. Not starting more games.", i, games, upstreamFailures,
						UPSTREAM_NOTE);
				break;
			}
		}
		log.info(summary(results));
	}

	private static String summary(List<GameResult> results) {
		LongSummaryStatistics scores = results.stream().mapToLong(GameResult::score).summaryStatistics();
		return "Played %d: passed %d (score above %d, not aborted), reached target %d, aborted %d; score lowest %d, highest %d"
				.formatted(results.size(), results.stream().filter(GameResult::passed).count(), GameResult.PASS_SCORE,
						count(results, Status.TARGET_REACHED), count(results, Status.ABORTED), scores.getMin(), scores.getMax());
	}

	private static long count(List<GameResult> results, Status status) {
		return results.stream().filter(r -> r.status() == status).count();
	}

	private static boolean isUpstreamFailure(GameResult result) {
		return result.status() == Status.ABORTED && result.note().equals(UPSTREAM_NOTE);
	}

	private static long option(ApplicationArguments args, String name, long fallback) {
		List<String> values = args.getOptionValues(name);
		if (values == null || values.isEmpty()) {
			return fallback;
		}
		long value = Long.parseLong(values.get(0));
		if (value < 1) {
			throw new IllegalArgumentException("--" + name + " must be at least 1");
		}
		return value;
	}
}
