package com.pureplayer.video

import android.content.Context
import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object BiometricHardwareGuard {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "pure_player_privacy_bio_key_v1"

    sealed class CipherResult {
        data class Ready(val cipher: Cipher) : CipherResult()
        object EnrollmentChangedOrUnbound : CipherResult()
        object Unavailable : CipherResult()
    }

    fun bindKeyAfterPasswordVerified(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            PlaybackStore.setBiometricPasswordVerified(context, false)
            return
        }
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)

            keyGenerator.init(builder.build())
            keyGenerator.generateKey()

            val secretKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (secretKey != null) {
                val testCipher = Cipher.getInstance(
                    "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_CBC}/${KeyProperties.ENCRYPTION_PADDING_PKCS7}"
                )
                testCipher.init(Cipher.ENCRYPT_MODE, secretKey)
                PlaybackStore.setBiometricPasswordVerified(context, true)
            } else {
                PlaybackStore.setBiometricPasswordVerified(context, false)
            }
        } catch (_: Exception) {
            PlaybackStore.setBiometricPasswordVerified(context, false)
        }
    }

    fun prepareCipherForBiometricPrompt(context: Context): CipherResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return CipherResult.Unavailable
        if (!PlaybackStore.isBiometricPasswordVerified(context)) {
            return CipherResult.EnrollmentChangedOrUnbound
        }
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: return CipherResult.EnrollmentChangedOrUnbound

            val cipher = Cipher.getInstance(
                "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_CBC}/${KeyProperties.ENCRYPTION_PADDING_PKCS7}"
            )
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            CipherResult.Ready(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            PlaybackStore.setBiometricPasswordVerified(context, false)
            try {
                val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                ks.deleteEntry(KEY_ALIAS)
            } catch (e: Exception) { Log.w("BiometricHardwareGuard", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
            CipherResult.EnrollmentChangedOrUnbound
        } catch (_: Exception) {
            CipherResult.Unavailable
        }
    }

    fun clearBiometricKey(context: Context) {
        PlaybackStore.setBiometricPasswordVerified(context, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                if (ks.containsAlias(KEY_ALIAS)) {
                    ks.deleteEntry(KEY_ALIAS)
                }
            } catch (e: Exception) { Log.w("BiometricHardwareGuard", "Handled non-fatal exception: ${e.javaClass.simpleName}") }
        }
    }
}
