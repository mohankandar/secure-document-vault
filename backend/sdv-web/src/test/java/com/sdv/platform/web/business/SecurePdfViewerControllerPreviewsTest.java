package com.sdv.platform.web.business;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.viewer.DocumentViewerService;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The previews endpoint uses the viewer's VIEW_PDF check and the viewer's no-store headers. */
class SecurePdfViewerControllerPreviewsTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOC = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    private final CapabilityPolicyService capabilities = mock(CapabilityPolicyService.class);
    private final DocumentViewerService viewer = mock(DocumentViewerService.class);
    private final SecurePdfViewerController controller = new SecurePdfViewerController(
            new SecurityContextFacade(), new AuthorizationService(mock(AuditEventPort.class), capabilities), viewer);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(boolean mayView) {
        SdvPrincipal principal = new SdvPrincipal(TENANT, "someone", Set.of(SdvRole.BUSINESS_ADMIN));
        when(capabilities.has(principal, Capability.VIEW_PDF)).thenReturn(mayView);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void aRoleWithoutViewPdfIsRefusedBeforeAnythingIsDecrypted() {
        signIn(false);
        assertThrows(AccessDeniedException.class, () -> controller.previews(DOC, 1, 12, null));
        verifyNoInteractions(viewer);
    }

    @Test
    void previewsUseTheViewersNoStoreHeaders() {
        signIn(true);
        when(viewer.previews(TENANT, DOC, 1, 12, null, com.sdv.platform.application.access.Clearance.STANDARD_ONLY, "someone", null)).thenReturn(new DocumentViewerService.PagePreviews(DOC, 1, 1, List.of()));
        var response = controller.previews(DOC, 1, 12, null);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        verify(viewer).previews(eq(TENANT), eq(DOC), eq(1), eq(12), any(),
                eq(com.sdv.platform.application.access.Clearance.STANDARD_ONLY), eq("someone"), isNull());
    }
}
