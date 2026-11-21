package com.sdv.platform.security.auth;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The signed-in principal for a controller. Task 0038: no sign-in is 401 SDV-AUT-001; the wrong kind of sign-in for
 * the address (a person on a sending system's address, or a sending system on a person's) is 403 SDV-AUT-002, since
 * the caller is signed in and signing in again won't help.
 */
@Component
public class SecurityContextFacade {
    public SdvPrincipal requirePrincipal() {
        Object principal = authentication().getPrincipal();
        if (principal instanceof SdvPrincipal person) return person;
        if (principal instanceof IngestionSourcePrincipal) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.AUT_002, SignInAnswer.FOR_PEOPLE,
                    "principal=sending system on an address for people");
        }
        throw notSignedIn();
    }

    public IngestionSourcePrincipal requireIngestionSourcePrincipal() {
        Object principal = authentication().getPrincipal();
        if (principal instanceof IngestionSourcePrincipal source) return source;
        if (principal instanceof SdvPrincipal) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.AUT_002, SignInAnswer.FOR_SENDING_SYSTEMS,
                    "principal=person on an address for sending systems");
        }
        throw notSignedIn();
    }

    private static Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) throw notSignedIn();
        return authentication;
    }

    private static CodedRefusal.Unauthorized notSignedIn() {
        return new CodedRefusal.Unauthorized(SdvErrorCode.AUT_001, SignInAnswer.SIGN_IN_AGAIN, "no Vero sign-in on the request");
    }
}
