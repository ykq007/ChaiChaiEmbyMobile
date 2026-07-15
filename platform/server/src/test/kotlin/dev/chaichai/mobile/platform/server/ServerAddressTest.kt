package dev.chaichai.mobile.platform.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerAddressTest {
    @Test
    fun hostname_defaults_to_https_and_normalizes_trailing_slashes() {
        val result = ServerAddress.parse("media.example/emby///")

        assertEquals("https://media.example/emby", (result as AddressValidation.Valid).address.value)
    }

    @Test
    fun deployment_prefix_is_preserved_when_building_api_urls() {
        val address = (ServerAddress.parse("https://media.example/house/emby/") as AddressValidation.Valid).address

        assertEquals(
            "https://media.example/house/emby/System/Info/Public",
            address.apiUrl("System/Info/Public").toString(),
        )
    }

    @Test
    fun query_fragment_and_ui_paths_have_distinct_corrective_guidance() {
        assertEquals(
            AddressProblem.QueryOrFragment,
            (ServerAddress.parse("https://media.example/emby?api=1") as AddressValidation.Invalid).problem,
        )
        assertEquals(
            AddressProblem.QueryOrFragment,
            (ServerAddress.parse("https://media.example/emby#login") as AddressValidation.Invalid).problem,
        )
        assertEquals(
            AddressProblem.WebInterfacePath,
            (ServerAddress.parse("https://media.example/web/index.html") as AddressValidation.Invalid).problem,
        )
    }
}
