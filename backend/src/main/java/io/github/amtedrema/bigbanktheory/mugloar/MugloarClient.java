package io.github.amtedrema.bigbanktheory.mugloar;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import io.github.amtedrema.bigbanktheory.mugloar.MugloarException.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec;
import org.springframework.web.client.RestClient.ResponseSpec;
import org.springframework.web.client.RestClientException;

/**
 * The only class that talks to the Mugloar API. The real API differs from its docs, so every
 * failure is turned into a {@link MugloarException} here and nothing else leaks out.
 */
@Component
public class MugloarClient {

	private static final Logger log = LoggerFactory.getLogger(MugloarClient.class);

	private final RestClient client;

	public MugloarClient(RestClient.Builder builder, @Value("${mugloar.base-url}") String baseUrl) {
		this.client = builder.baseUrl(baseUrl).build();
	}

	public GameState start() {
		return send(client.post().uri("/game/start"), Kind.UPSTREAM_INVALID, r -> r.body(GameState.class));
	}

	/** Encrypted ads are decoded here, so the rest of the code only sees plain ads. */
	public List<Ad> messages(String gameId) {
		List<Ad> ads = retryOnce(() -> send(client.get().uri("/{gameId}/messages", gameId), Kind.UPSTREAM_INVALID,
				r -> r.body(new ParameterizedTypeReference<List<Ad>>() { })));
		return ads.stream().map(Ad::decoded).toList();
	}

	/** A 400 here means the ad is gone (expired or unknown). */
	public SolveResult solve(String gameId, String adId) {
		return send(client.post().uri("/{gameId}/solve/{adId}", gameId, adId), Kind.AD_UNAVAILABLE,
				r -> r.body(SolveResult.class));
	}

	public List<ShopItem> shop(String gameId) {
		return retryOnce(() -> send(client.get().uri("/{gameId}/shop", gameId), Kind.UPSTREAM_INVALID,
				r -> r.body(new ParameterizedTypeReference<List<ShopItem>>() { })));
	}

	public BuyResult buy(String gameId, String itemId) {
		return send(client.post().uri("/{gameId}/shop/buy/{itemId}", gameId, itemId), Kind.UPSTREAM_INVALID,
				r -> r.body(BuyResult.class));
	}

	/** Sends the request and reads the body; badRequestKind is what a 400 means for this call. */
	private <T> T send(RequestHeadersSpec<?> request, Kind badRequestKind, Function<ResponseSpec, T> read) {
		T body;
		try {
			ResponseSpec response = request.retrieve().onStatus(HttpStatusCode::isError, (req, res) -> {
				if (res.getStatusCode().value() == 429) {
					log.warn("Upstream answered 429: rate limited");
				}
				throw new MugloarException(kindOf(res.getStatusCode(), badRequestKind),
						"Upstream answered " + res.getStatusCode().value());
			});
			body = read.apply(response);
		} catch (RestClientException e) {
			// Spring wraps every I/O failure (connect, timeout, dropped body) with the IOException as cause
			boolean io = e.getCause() instanceof IOException;
			throw new MugloarException(io ? Kind.UPSTREAM_UNAVAILABLE : Kind.UPSTREAM_INVALID,
					io ? "Upstream not reachable" : "Upstream sent an unreadable response");
		}
		if (body == null) {
			throw new MugloarException(Kind.UPSTREAM_INVALID, "Upstream sent an empty response");
		}
		return body;
	}

	private static Kind kindOf(HttpStatusCode status, Kind badRequestKind) {
		if (status.value() == 404) {
			return Kind.GAME_NOT_FOUND;
		}
		if (status.value() == 410) {
			return Kind.GAME_OVER;
		}
		if (status.value() == 400) {
			return badRequestKind;
		}
		if (status.value() == 429 || status.is5xxServerError()) {
			return Kind.UPSTREAM_UNAVAILABLE;
		}
		return Kind.UPSTREAM_INVALID;
	}

	/** Reads are safe to repeat, so they get one more try. Posts are never repeated. */
	private static <T> T retryOnce(Supplier<T> read) {
		try {
			return read.get();
		} catch (MugloarException e) {
			if (e.kind() != Kind.UPSTREAM_UNAVAILABLE) {
				throw e;
			}
			return read.get();
		}
	}
}
