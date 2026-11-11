// SPDX-License-Identifier: GPL-3.0-only
// Region and device information adapted from ONextBox 17.0.0.100.
package com.cosmod.launcherglass.ui

import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object OnextDeviceInfo {
    val marketName get()=readHomeSystemProperty("ro.vendor.oplus.market.name")
        ?: readHomeSystemProperty("ro.oplus.market.name") ?: Build.MODEL
    val systemVersion get()=Build.DISPLAY.ifBlank {Build.VERSION.INCREMENTAL}
    val architecture get()=Build.SUPPORTED_ABIS.joinToString(" / ")
    val kernelVersion get()=System.getProperty("os.version")?.lineSequence()?.firstOrNull()?.trim().orEmpty()

    /** Version query only. This never asks the root manager to execute a command. */
    fun rootVersion():String? {
        var process:Process?=null
        return try {
            val child=ProcessBuilder("su","-v").redirectErrorStream(true).start()
            process=child
            if(child.waitFor(700,TimeUnit.MILLISECONDS) && child.exitValue()==0)
                child.inputStream.bufferedReader().use {it.readLine()?.trim()?.take(120)?.takeIf(String::isNotBlank)}
            else null
        } catch(_:Exception){null}
        finally {process?.destroy()}
    }

    fun region(context: Context): String {
        val nvid = readHomeSystemProperty("ro.build.oplus_nv_id")?.trim()
        val nvidRegion = nvid?.let(::mapOplusNvidRegion)
        if (nvidRegion != null) return nvidRegion

        val propertyRegion = listOf(
            "ro.oplus.regionmark",
            "ro.oplus.region",
            "ro.vendor.oplus.regionmark",
            "persist.sys.oplus.region",
            "ro.product.locale.region",
        ).asSequence()
            .mapNotNull(::readHomeSystemProperty)
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?.let(::normalizeHomeRegionCode)

        if (propertyRegion != null) {
            return formatHomeRegion(propertyRegion)
        }

        if (!nvid.isNullOrBlank() && !nvid.equals("null", ignoreCase = true)) {
            return "NV $nvid"
        }

        val localeRegion = context.resources.configuration.locales[0].country
            .ifBlank { "XX" }
            .uppercase(Locale.ROOT)
        return formatHomeRegion(localeRegion)
    }

    private fun mapOplusNvidRegion(nvid: String): String? = when (nvid) {
        "10010111" -> formatHomeRegion("CN")
        "00011010" -> formatHomeRegion("TW")
        "00110111" -> formatHomeRegion("RU")
        "01000100" -> formatHomeRegion("GDPR_EU")
        "10001101" -> formatHomeRegion("GDPR_EUROPE")
        "00011011" -> formatHomeRegion("IN")
        "00110011" -> formatHomeRegion("ID")
        "00111000" -> formatHomeRegion("MY")
        "00111001" -> formatHomeRegion("TH")
        "00111110" -> formatHomeRegion("PH")
        "10000011" -> formatHomeRegion("SA")
        "10011010" -> formatHomeRegion("LATAM")
        "10011110" -> formatHomeRegion("BR")
        "10100110" -> formatHomeRegion("MEA")
        else -> null
    }

    private fun normalizeHomeRegionCode(raw: String): String? {
        val normalized = raw
            .replace('-', '_')
            .substringAfterLast('_')
            .uppercase(Locale.ROOT)
        val mapped = when (normalized) {
            "INDIA" -> "IN"
            "CHINA" -> "CN"
            "HONGKONG", "HONG_KONG" -> "HK"
            "TAIWAN" -> "TW"
            "GLOBAL", "ROW", "WW", "EUEX" -> "GLO"
            else -> normalized
        }
        return mapped.takeIf { code ->
            code.length in 2..3 && code.all { it in 'A'..'Z' }
        }
    }

    private fun formatHomeRegion(code: String): String {
        val normalized = code.uppercase(Locale.ROOT)
        return when (normalized) {
            "CN" -> "CN China 🇨🇳"
            "TW" -> "TW Taiwan 🇹🇼"
            "HK" -> "HK Hong Kong 🇭🇰"
            "RU" -> "RU Russia 🇷🇺"
            "EU", "GDPR_EU" -> "GDPR EU 🇪🇺"
            "GDPR_EUROPE" -> "GDPR Europe 🇪🇺"
            "IN" -> "IN India 🇮🇳"
            "ID" -> "ID Indonesia 🇮🇩"
            "MY" -> "MY Malaysia 🇲🇾"
            "TH" -> "TH Thailand 🇹🇭"
            "PH" -> "PH Philippines 🇵🇭"
            "SA" -> "SA Saudi Arabia 🇸🇦"
            "LATAM" -> "LATAM Latin America"
            "BR" -> "BR Brazil 🇧🇷"
            "MEA", "ME" -> "MEA The Middle East and Africa"
            "GLO", "GLOBAL" -> "GLO Global 🌐"
            else -> {
                val flag = regionalFlag(normalized)
                flag?.let { "$normalized $it" } ?: normalized
            }
        }
    }

    private fun regionalFlag(code: String): String? {
        if (code.length != 2 || code.any { it !in 'A'..'Z' }) return null
        val builder = StringBuilder()
        code.forEach { letter ->
            builder.appendCodePoint(0x1F1E6 + (letter - 'A'))
        }
        return builder.toString()
    }

    private fun readHomeSystemProperty(key: String): String? {
        return runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as? String
        }.getOrNull()?.takeIf(String::isNotBlank)
    }
}
