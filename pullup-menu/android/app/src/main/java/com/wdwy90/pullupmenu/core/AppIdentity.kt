package com.wdwy90.pullupmenu.core

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Headers that identify this app to Google APIs, so the Places key can be restricted to
 * "Android apps: com.wdwy90.pullupmenu + signing cert SHA-1" in the Cloud Console.
 */
object AppIdentity {
    @Volatile private var cached: Map<String, String>? = null

    fun headers(ctx: Context): Map<String, String> {
        cached?.let { return it }
        val pkg = ctx.packageName
        val cert = try {
            certSha1(ctx, pkg)
        } catch (e: Exception) {
            null
        }
        // Without a cert we don't cache, so the next call tries again.
        if (cert == null) return mapOf("X-Android-Package" to pkg)
        return mapOf("X-Android-Package" to pkg, "X-Android-Cert" to cert).also { cached = it }
    }

    private fun certSha1(ctx: Context, pkg: String): String? {
        val si = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo ?: return null
        val sig = if (si.hasMultipleSigners()) si.apkContentsSigners?.firstOrNull()
        else si.signingCertificateHistory?.lastOrNull() // last = current signer
        return sig?.let { sha1Hex(it.toByteArray()) }
    }

    /** Uppercase hex, no separators: the format X-Android-Cert expects. */
    fun sha1Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-1").digest(bytes)
        val hex = "0123456789ABCDEF"
        return buildString(d.size * 2) {
            for (b in d) {
                val v = b.toInt() and 0xFF
                append(hex[v ushr 4])
                append(hex[v and 0xF])
            }
        }
    }
}
