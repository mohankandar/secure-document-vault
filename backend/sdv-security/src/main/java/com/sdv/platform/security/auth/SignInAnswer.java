package com.sdv.platform.security.auth;

import com.sdv.platform.domain.error.SdvErrorCode;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;

/**
 * Task 0038: how Vero answers a sign-in that the security filters refused, before any controller ran. The filters
 * put the answer on the request and call {@code sendError(status)}; Vero's /error page reads it, logs one line with
 * the code, the reference and {@link #logDetail()}, and writes the one error body with {@link #message()}.
 * <p>
 * The message is a fixed sentence. It never says whether an account exists and never holds anything from the token.
 * The log detail names what was checked (a claim's name, a host, a reason class), never a claim's value or the token.
 */
public record SignInAnswer(int status, SdvErrorCode code, String message, String logDetail) {
    /** The request attribute the answer travels in, from the filter to the error page. */
    public static final String ATTRIBUTE = SignInAnswer.class.getName();

    public static final String SIGN_IN_AGAIN = "Please sign in again.";
    public static final String FOR_SENDING_SYSTEMS = "This address is for sending systems only.";
    public static final String FOR_PEOPLE = "This address is for people, not sending systems.";
    public static final String KEYS_UNAVAILABLE = "Vero can't check sign-ins right now because the sign-in service "
            + "isn't answering. Try again in a few minutes.";
    public static final String CLAIMS_NOT_USABLE = "Your sign-in is missing details Vero needs. Your IT team can fix this.";
    public static final String NO_CONNECTION = "Vero isn't set up to accept this sign-in. Your IT team can fix this.";
    public static final String NO_ROLE = "You're signed in, but you don't have access to Vero. Ask your IT team to add "
            + "you to a Vero group.";
    public static final String LOCAL_HEADERS_PREFIX = "Local sign-in headers are not valid: ";
    public static final String ROLES_NEVER_TOGETHER = "The roles from this sign-in can never be held together, so none "
            + "of them were given. Ask your IT team to correct your sign-in groups.";
    public static final String CONFLICT_NOT_RECORDED = "Vero couldn't record a role conflict in this sign-in, so the "
            + "sign-in was refused. Try again in a few minutes.";
    public static final String SIGN_IN_OFF = "Sign-in is switched off for Vero, so nobody can sign in yet. Your IT team "
            + "has been told.";
    public static final String ROLE_LACKS_THIS = "Your role doesn't include this action. Ask someone who has it.";
    /** Task 0041 (SDV-AUT-010). */
    public static final String CHOOSE_ENTITY = "Choose which entity to work in first.";
    /** Task 0041 (SDV-AUT-011). */
    public static final String NOT_A_SENDING_SYSTEM = "This client isn't set up to send documents to Vero. Your IT team can "
            + "fix this.";
    /** Task 0041 (SDV-AUT-012). */
    public static final String CLAIM_NOT_BINDING = "The entity or sending system in this token doesn't match how this "
            + "client is set up in Vero. Your IT team can fix this.";
    /** Task 0041 (SDV-AUT-013). */
    public static final String SOURCE_NOT_SET_UP = "The sending system this client is set up for doesn't exist in Vero. "
            + "Your IT team can fix this.";

    public SignInAnswer {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    public static SignInAnswer of(int status, SdvErrorCode code, String message, String logDetail) {
        return new SignInAnswer(status, code, message, logDetail);
    }

    /** Puts the answer on the request, for the error page. */
    public void attachTo(HttpServletRequest request) {
        request.setAttribute(ATTRIBUTE, this);
    }

    /** The answer the filters left on this request, or null. */
    public static SignInAnswer on(HttpServletRequest request) {
        return request != null && request.getAttribute(ATTRIBUTE) instanceof SignInAnswer answer ? answer : null;
    }
}
