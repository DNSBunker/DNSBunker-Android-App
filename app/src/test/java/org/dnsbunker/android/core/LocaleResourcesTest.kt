package org.dnsbunker.android.core

import android.content.res.Configuration
import android.os.LocaleList
import org.dnsbunker.android.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LocaleResourcesTest {
    @Test fun `five locales have translated actions and preserve security disclosures`() {
        val expected = mapOf("en" to "Enable protection", "de" to "Schutz aktivieren",
            "es" to "Activar protección", "fr" to "Activer la protection", "ru" to "Включить защиту")
        expected.forEach { (tag, label) ->
            val config = Configuration().apply { setLocales(LocaleList.forLanguageTags(tag)) }
            val context = RuntimeEnvironment.getApplication().createConfigurationContext(config)
            assertEquals(tag, label, context.getString(R.string.start))
            assertTrue(context.getString(R.string.vpn_body).contains("dnsbunker.org"))
            assertTrue(context.getString(R.string.local_dns_notice).contains("UDP"))
            assertTrue(context.getString(R.string.encrypted_only_body).contains("DoQ"))
            assertTrue(context.getString(R.string.wifi_exempt_detail, "TestWiFi").contains("TestWiFi"))
        }
    }
}
