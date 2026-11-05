package io.github.amtedrema.bigbanktheory.web;

import io.github.amtedrema.bigbanktheory.mugloar.MugloarException;
import io.github.amtedrema.bigbanktheory.mugloar.MugloarException.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as a ProblemDetail with a {@code code} the frontend can switch on. The
 * Mugloar detail texts are fixed here, so no exception message or upstream body can leak. Spring's own
 * request errors are handled by the base class and keep Spring's short text.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(MugloarException.class)
	ProblemDetail handleMugloar(MugloarException e) {
		HttpStatus status = statusOf(e.kind());
		if (status.is5xxServerError()) {
			log.warn("Mugloar call failed: {} ({})", e.kind(), e.getMessage());
		}
		return problem(status, detailOf(e.kind()), e.kind().name());
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception e) {
		log.error("Unexpected error", e);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", "INTERNAL_ERROR");
	}

	/** Spring's request errors get a code too: INVALID_INPUT for 400, INTERNAL_ERROR for 5xx, REQUEST_REJECTED otherwise. */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, status, request);
		if (response != null && response.getBody() instanceof ProblemDetail problem) {
			problem.setProperty("code", status.value() == 400 ? "INVALID_INPUT"
					: status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED");
		}
		return response;
	}

	private static ProblemDetail problem(HttpStatus status, String detail, String code) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return problem;
	}

	private static HttpStatus statusOf(Kind kind) {
		return switch (kind) {
			case AD_UNAVAILABLE -> HttpStatus.CONFLICT;
			case GAME_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case GAME_OVER -> HttpStatus.GONE;
			case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
			case UPSTREAM_INVALID -> HttpStatus.BAD_GATEWAY;
		};
	}

	private static String detailOf(Kind kind) {
		return switch (kind) {
			case AD_UNAVAILABLE -> "The ad is no longer available";
			case GAME_NOT_FOUND -> "The game was not found";
			case GAME_OVER -> "The game is over";
			case UPSTREAM_UNAVAILABLE -> "The game server is not reachable";
			case UPSTREAM_INVALID -> "The game server sent an unusable response";
		};
	}
}
