package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.TenantSettings;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0100 (VSEC-204): the address of every message comes from the install configuration, https only. */
class PublicUrlTest {

    @Test
    void httpsAddressesAreTakenAndPlainHttpOnlyInALocalStart() {
        assertEquals("https://vero.example.com", PublicUrl.of("https://vero.example.com/", false).address().orElseThrow().toString());
        assertEquals("https://vero.example.com/vero", PublicUrl.of(" https://vero.example.com/vero ", false).address().orElseThrow().toString());
        assertEquals("http://localhost:5183", PublicUrl.of("http://localhost:5183", true).address().orElseThrow().toString());
        for (String bad : new String[]{"http://vero.example.com", "vero.example.com", "ftp://vero.example.com",
                "https://user@vero.example.com", "https://vero.example.com/?a=1", "https://vero.example.com/#/home", "https:///x", "not a url"}) {
            assertThrows(StartupFailure.class, () -> PublicUrl.of(bad, false), bad);
        }
    }

    @Test
    void unsetIsLocalhostInALocalStartAndNothingElsewhere() {
        assertEquals(PublicUrl.LOCAL_DEFAULT, PublicUrl.of("", true).address().orElseThrow().toString());
        assertTrue(PublicUrl.of(null, false).address().isEmpty());
        assertTrue(PublicUrl.of("  ", false).address().isEmpty());
    }

    @Test
    void theScreenConnectsOnlyUnderTheInstallsAddress() {
        NotificationKit kit = new NotificationKit();
        var channels = kit.screens.channels(NotificationKit.TENANT);
        assertEquals("https://vero.example.test", channels.appAddress());
        assertTrue(channels.connected());

        var unset = new NotificationSettingsService(kit.settings, new com.documentplatform.notifications.spring.NotificationsProperties(),
                Map::of, PublicUrl.of("", false));
        CodedRefusal refusal = (CodedRefusal) assertThrows(RuntimeException.class, () -> unset.connect(NotificationKit.TENANT, ZoneId.of("UTC")));
        assertEquals("SDV-NTF-005", refusal.errorCode().code());
        assertFalse(unset.channels(NotificationKit.TENANT).connected(), "without the setting nothing counts as connected");
    }

    @Test
    void aCustomerConnectedUnderAnotherAddressIsMovedToTheInstalls() {
        NotificationKit kit = new NotificationKit();
        var before = kit.settings.tenant(NotificationKit.TENANT).orElseThrow();
        kit.settings.connectChannels(NotificationKit.TENANT, new TenantSettings(URI.create("https://look-alike.example.net"),
                ZoneId.of("Europe/Paris"), before.destinations()));
        assertFalse(kit.screens.channels(NotificationKit.TENANT).connected(), "another address is not connected");
        assertTrue(kit.screens.align(NotificationKit.TENANT));
        var after = kit.settings.tenant(NotificationKit.TENANT).orElseThrow();
        assertEquals("https://vero.example.test", after.appBaseUrl().toString());
        assertEquals(ZoneId.of("Europe/Paris"), after.zone(), "the zone stays");
        assertEquals(before.destinations(), after.destinations(), "the channels stay");
        assertFalse(kit.screens.align(NotificationKit.TENANT), "nothing to move the second time");
    }
}
