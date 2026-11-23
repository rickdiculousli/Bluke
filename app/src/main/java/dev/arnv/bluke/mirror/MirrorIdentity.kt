package dev.arnv.bluke.mirror

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** SHA-256 of a certificate's DER bytes: the fingerprint PROTOCOL.md pins. */
fun certificateFingerprint(certificate: Certificate): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded)

fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return ByteArray(length / 2) { i ->
        substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
    }
}

/**
 * The phone's Mirror identity: an EC P-256 key that never leaves the Android Keystore, with the
 * self-signed certificate the Keystore makes for it, and the one companion it has pinned.
 */
class MirrorIdentity(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    val certificate: X509Certificate by lazy {
        if (!keyStore.containsAlias(KEY_ALIAS)) generateKey()
        keyStore.getCertificate(KEY_ALIAS) as X509Certificate
    }
    val fingerprint: ByteArray by lazy { certificateFingerprint(certificate) }

    @Synchronized
    fun pinnedCompanion(): PinnedCompanion? {
        val fingerprint = prefs.getString(PREF_COMPANION_FINGERPRINT, null)?.hexToBytes() ?: return null
        if (fingerprint.size != 32) return null
        return PinnedCompanion(fingerprint, prefs.getString(PREF_COMPANION_NAME, null) ?: "companion")
    }

    /** Re-pairing replaces the pinned companion: the phone trusts one at a time. */
    @Synchronized
    fun pin(companion: PinnedCompanion) = prefs.edit(commit = true) {
        putString(PREF_COMPANION_FINGERPRINT, companion.fingerprint.toHex())
        putString(PREF_COMPANION_NAME, companion.name)
    }

    @Synchronized
    fun forget() = prefs.edit(commit = true) {
        remove(PREF_COMPANION_FINGERPRINT)
        remove(PREF_COMPANION_NAME)
    }

    /**
     * TLS 1.3 with the Keystore key. The trust manager accepts any client certificate during the
     * handshake (the library still checks its CertificateVerify signature); the caller compares the
     * peer's fingerprint with the pin before it reads or writes a single message.
     */
    fun sslContext(): SSLContext {
        certificate // make sure the key exists
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, null) }
            .keyManagers
        return SSLContext.getInstance("TLSv1.3").apply {
            init(keyManagers, arrayOf<TrustManager>(AcceptAnyCertificate), null)
        }
    }

    private fun generateKey() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=Bluke Mirror"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - DAY_MILLIS))
            .setCertificateNotAfter(Date(now + 30 * 365 * DAY_MILLIS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    private object AcceptAnyCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("no client certificate")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw java.security.cert.CertificateException("the phone is never a TLS client")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bluke_mirror_identity"
        const val PREFS = "mirror_prefs"
        const val PREF_COMPANION_FINGERPRINT = "companion_fingerprint"
        const val PREF_COMPANION_NAME = "companion_name"
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
