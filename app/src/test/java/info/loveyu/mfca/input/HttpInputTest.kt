package info.loveyu.mfca.input

import info.loveyu.mfca.config.HttpInputConfig
import info.loveyu.mfca.config.LinkConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpInputTest {
    @Test
    fun `shared http prefers explicit path before catch all`() {
        val sharedInput = SharedHttpInput(LinkConfig(id = "http_server", dsn = "http://0.0.0.0:31774"))
        val catchAll = HttpVirtualInput(HttpInputConfig(name = "automate_notify", dsn = "?bearerToken=mcft-ABC"))
        val exactPath = HttpVirtualInput(
            HttpInputConfig(
                name = "fcitx5_clipboard",
                dsn = "?queryTokenKey=apikey&queryTokenValue=ABC&methods=POST",
                paths = listOf("/fcitx5/clipboard")
            )
        )

        sharedInput.addVirtualInput(catchAll)
        sharedInput.addVirtualInput(exactPath)

        val matches = sharedInput.findMatchingVirtualInputs("/fcitx5/clipboard")

        assertEquals(listOf(exactPath), matches)
    }

    @Test
    fun `shared http falls back to catch all when no explicit path matches`() {
        val sharedInput = SharedHttpInput(LinkConfig(id = "http_server", dsn = "http://0.0.0.0:31774"))
        val catchAll = HttpVirtualInput(HttpInputConfig(name = "automate_notify", dsn = "?bearerToken=mcft-ABC"))
        val exactPath = HttpVirtualInput(
            HttpInputConfig(
                name = "phone_notify",
                dsn = "?bearerToken=mcft-ABC",
                paths = listOf("/phone/notify")
            )
        )

        sharedInput.addVirtualInput(catchAll)
        sharedInput.addVirtualInput(exactPath)

        val matches = sharedInput.findMatchingVirtualInputs("/another/path")

        assertEquals(listOf(catchAll), matches)
    }

    @Test
    fun `query parameters are matched exactly`() {
        val queryParams = HttpInput.parseQueryParameters("apikey=ABC123&other=value")

        assertTrue(queryParams["apikey"] == "ABC123")
        assertFalse(queryParams["apikey"] == "ABC")
    }
}
