package com.aashishgodambe.arcana.core.ai.catalog

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aashishgodambe.arcana.core.ai.model.InferenceLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [CloudMultimodalCatalogProvider] — the cloud escalation. The parse() cases are deterministic (org.json
 * runs on device, no network); the last test makes ONE real multimodal call to identify the *unowned*
 * Aang #406 box (the escalation branch), proving the Day-4 DoD that an unknown pop resolves via cloud.
 */
@RunWith(AndroidJUnit4::class)
class CloudMultimodalCatalogProviderTest {

    private val provider = CloudMultimodalCatalogProvider()

    @Test
    fun parses_structured_json_into_a_cloud_entry() {
        val json = """{"character":"Aang with Armor","franchise":"Avatar: The Last Airbender",""" +
            """"number":"406","series":"Pop! Digital","finish":"Metallic"}"""
        val entry = provider.parse(json, latencyMs = 1234)
        assertNotNull(entry)
        assertEquals("Aang with Armor - Metallic", entry!!.name)   // finish appended
        assertEquals("406", entry.number)
        assertEquals(InferenceLocation.Cloud, entry.executedOn)
        assertEquals(1234L, entry.latencyMs)
        assertEquals(0.8f, entry.confidence, 0.001f)
    }

    @Test
    fun returns_null_when_cloud_cannot_identify() {
        assertNull(provider.parse("""{"character":"","franchise":""}""", 0))
    }

    @Test
    fun returns_null_without_an_image() = runBlocking {
        assertNull(provider.lookup(CatalogQuery(popNumber = "406", franchise = "Avatar", character = "Aang")))
    }

    @Test
    fun identifies_an_unowned_pop_from_the_real_image_via_cloud() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bitmap = ctx.assets.open("funko_box.png").use { BitmapFactory.decodeStream(it) }

        val entry = provider.lookup(
            CatalogQuery(popNumber = "406", franchise = "Avatar", character = "Aang", image = bitmap),
        )

        assertNotNull("cloud should identify the Aang #406 box", entry)
        assertEquals(InferenceLocation.Cloud, entry!!.executedOn)
        assertTrue("name was ${entry.name}", entry.name.contains("Aang", ignoreCase = true))
        assertNotNull("cloud latency should be recorded", entry.latencyMs)
    }
}
