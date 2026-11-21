package com.pureplayer.video

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class VlcEqPreset(
    val name: String,
    val preAmpDb: Float,
    val bandsDb: FloatArray, // 10-band VLC reference curve mapped dynamically to device band count
    val isCustom: Boolean = false
)

data class AudioEffectsSnapshot(
    val enabled: Boolean,
    val presetName: String,
    val preAmpDb: Float,
    val customBandsDb: List<Float>,
    val bassBoostStrength: Int,     // 0..1000
    val virtualizerStrength: Int,   // 0..1000
    val volumeBoostPercent: Int     // 100..200
)

/**
 * Unified Android AudioFX manager (Equalizer, BassBoost, Virtualizer, LoudnessEnhancer)
 * supporting all 16 VLC presets, device built-in presets, custom saved presets, and safe
 * audioSessionId attachment/release without leaks or crashes.
 */
object VexoAudioEffectsManager {
    private const val PREF_NAME = "pure_video_playback_store"
    private const val KEY_FX_ENABLED = "vexo_fx_enabled_v1"
    private const val KEY_FX_PRESET_NAME = "vexo_fx_preset_name_v1"
    private const val KEY_FX_PREAMP_DB = "vexo_fx_preamp_db_v1"
    private const val KEY_FX_BANDS_CSV = "vexo_fx_bands_csv_v1"
    private const val KEY_FX_BASS = "vexo_fx_bass_strength_v1"
    private const val KEY_FX_VIRTUALIZER = "vexo_fx_virtualizer_strength_v1"
    private const val KEY_FX_BOOST_PCT = "vexo_fx_volume_boost_pct_v1"
    private const val KEY_FX_CUSTOM_PRESETS = "vexo_fx_custom_presets_json_v1"

    val vlcPresets: List<VlcEqPreset> = listOf(
        VlcEqPreset("Flat", 0f, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        VlcEqPreset("Classical", 0f, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -4.5f, -4.5f, -4.5f, -6f)),
        VlcEqPreset("Club", 0f, floatArrayOf(0f, 0f, 4.8f, 3.2f, 3.2f, 3.2f, 1.6f, 0f, 0f, 0f)),
        VlcEqPreset("Dance", 0f, floatArrayOf(6f, 4.5f, 1.5f, 0f, 0f, -3.5f, -4.5f, -4.5f, 0f, 0f)),
        VlcEqPreset("Full Bass", 0f, floatArrayOf(-5f, 6f, 6f, 3.5f, 1f, -2.5f, -5f, -6f, -6.5f, -6.5f)),
        VlcEqPreset("Full Treble", 0f, floatArrayOf(-6f, -6f, -6f, -2.5f, 1.5f, 6f, 9f, 9f, 9f, 10f)),
        VlcEqPreset("Headphones", 0f, floatArrayOf(3f, 6.5f, 3f, -2f, -1.5f, 1f, 3f, 5.5f, 7.5f, 8.5f)),
        VlcEqPreset("Large Hall", 0f, floatArrayOf(6.2f, 6.2f, 3.5f, 3.5f, 0f, -2.5f, -2.5f, -2.5f, 0f, 0f)),
        VlcEqPreset("Live", 0f, floatArrayOf(-2.5f, 0f, 2.4f, 3.2f, 3.5f, 3.5f, 2.4f, 1.6f, 1.6f, 1.2f)),
        VlcEqPreset("Party", 0f, floatArrayOf(4.5f, 4.5f, 0f, 0f, 0f, 0f, 0f, 0f, 4.5f, 4.5f)),
        VlcEqPreset("Pop", 0f, floatArrayOf(-1f, 3f, 4.5f, 4.8f, 3.2f, 0f, -1.5f, -1.5f, -1f, -1f)),
        VlcEqPreset("Reggae", 0f, floatArrayOf(0f, 0f, 0f, -3.5f, 0f, 4f, 4f, 0f, 0f, 0f)),
        VlcEqPreset("Rock", 0f, floatArrayOf(4.8f, 2.8f, -3.2f, -4.8f, -2f, 2.4f, 5.2f, 6.5f, 6.5f, 6.5f)),
        VlcEqPreset("Ska", 0f, floatArrayOf(-1.5f, -2.8f, -2.4f, 0f, 2.4f, 3.5f, 5.2f, 5.8f, 6.5f, 5.8f)),
        VlcEqPreset("Soft", 0f, floatArrayOf(2.8f, 1f, 0f, -1.5f, 0f, 2.4f, 4.8f, 5.8f, 6.5f, 7.2f)),
        VlcEqPreset("Techno", 0f, floatArrayOf(4.8f, 3.5f, 0f, -3.5f, -3f, 0f, 4.8f, 5.8f, 5.8f, 5.5f))
    )

    private var activeSessionId: Int = 0
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    var supportsEqualizer: Boolean = true
        private set
    var supportsBassBoost: Boolean = true
        private set
    var supportsVirtualizer: Boolean = true
        private set
    var supportsLoudnessEnhancer: Boolean = true
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadSnapshot(context: Context): AudioEffectsSnapshot {
        val p = prefs(context)
        val enabled = p.getBoolean(KEY_FX_ENABLED, false)
        val presetName = p.getString(KEY_FX_PRESET_NAME, "Flat") ?: "Flat"
        val preAmp = p.getFloat(KEY_FX_PREAMP_DB, 0f).coerceIn(-12f, 12f)
        val csv = p.getString(KEY_FX_BANDS_CSV, "") ?: ""
        val bands = if (csv.isNotBlank()) {
            csv.split(",").mapNotNull { it.trim().toFloatOrNull() }
        } else {
            List(5) { 0f }
        }
        val bass = p.getInt(KEY_FX_BASS, 0).coerceIn(0, 1000)
        val virt = p.getInt(KEY_FX_VIRTUALIZER, 0).coerceIn(0, 1000)
        val boost = p.getInt(KEY_FX_BOOST_PCT, 100).coerceIn(100, 200)
        return AudioEffectsSnapshot(enabled, presetName, preAmp, bands, bass, virt, boost)
    }

    fun saveSnapshot(context: Context, snap: AudioEffectsSnapshot) {
        prefs(context).edit()
            .putBoolean(KEY_FX_ENABLED, snap.enabled)
            .putString(KEY_FX_PRESET_NAME, snap.presetName)
            .putFloat(KEY_FX_PREAMP_DB, snap.preAmpDb.coerceIn(-12f, 12f))
            .putString(KEY_FX_BANDS_CSV, snap.customBandsDb.joinToString(","))
            .putInt(KEY_FX_BASS, snap.bassBoostStrength.coerceIn(0, 1000))
            .putInt(KEY_FX_VIRTUALIZER, snap.virtualizerStrength.coerceIn(0, 1000))
            .putInt(KEY_FX_BOOST_PCT, snap.volumeBoostPercent.coerceIn(100, 200))
            .apply()
    }

    fun getCustomPresets(context: Context): List<VlcEqPreset> {
        val raw = prefs(context).getString(KEY_FX_CUSTOM_PRESETS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = mutableListOf<VlcEqPreset>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue
                val preAmp = obj.optDouble("preAmp", 0.0).toFloat()
                val bArr = obj.optJSONArray("bands") ?: JSONArray()
                val bands = FloatArray(bArr.length()) { idx -> bArr.optDouble(idx, 0.0).toFloat() }
                list.add(VlcEqPreset(name, preAmp, bands, isCustom = true))
            }
            list
        }.getOrDefault(emptyList())
    }

    fun saveCustomPreset(context: Context, name: String, preAmpDb: Float, bandsDb: List<Float>) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val existing = getCustomPresets(context).filterNot { it.name.equals(cleanName, ignoreCase = true) }.toMutableList()
        existing.add(0, VlcEqPreset(cleanName, preAmpDb, bandsDb.toFloatArray(), isCustom = true))
        persistCustomPresets(context, existing)
    }

    fun renameCustomPreset(context: Context, oldName: String, newName: String) {
        val cleanNew = newName.trim()
        if (cleanNew.isEmpty()) return
        val updated = getCustomPresets(context).map {
            if (it.name.equals(oldName, ignoreCase = true)) it.copy(name = cleanNew) else it
        }
        persistCustomPresets(context, updated)
    }

    fun deleteCustomPreset(context: Context, name: String) {
        val updated = getCustomPresets(context).filterNot { it.name.equals(name, ignoreCase = true) }
        persistCustomPresets(context, updated)
    }

    private fun persistCustomPresets(context: Context, presets: List<VlcEqPreset>) {
        val arr = JSONArray()
        for (p in presets) {
            val obj = JSONObject()
            obj.put("name", p.name)
            obj.put("preAmp", p.preAmpDb.toDouble())
            val bArr = JSONArray()
            p.bandsDb.forEach { bArr.put(it.toDouble()) }
            obj.put("bands", bArr)
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_FX_CUSTOM_PRESETS, arr.toString()).apply()
    }

    fun getAllPresets(context: Context): List<VlcEqPreset> = getCustomPresets(context) + vlcPresets

    /**
     * Maps any 10-band or N-band preset curve cleanly to the device's hardware band count.
     */
    fun resampleBands(sourceBands: FloatArray, targetBandCount: Int): FloatArray {
        if (targetBandCount <= 0) return FloatArray(0)
        if (sourceBands.isEmpty()) return FloatArray(targetBandCount) { 0f }
        if (sourceBands.size == targetBandCount) return sourceBands.copyOf()
        return FloatArray(targetBandCount) { i ->
            val ratio = if (targetBandCount == 1) 0f else i.toFloat() / (targetBandCount - 1).toFloat()
            val srcIdx = (ratio * (sourceBands.size - 1)).toInt().coerceIn(0, sourceBands.lastIndex)
            sourceBands[srcIdx]
        }
    }

    @Synchronized
    fun attachToAudioSession(context: Context, audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (activeSessionId != audioSessionId) {
            releaseAll()
            activeSessionId = audioSessionId
            try {
                equalizer = Equalizer(0, audioSessionId)
                supportsEqualizer = true
            } catch (e: Exception) {
                supportsEqualizer = false
                Log.w("VexoAudioFX", "Equalizer unsupported: ${e.javaClass.simpleName}")
            }
            try {
                bassBoost = BassBoost(0, audioSessionId)
                supportsBassBoost = bassBoost?.strengthSupported == true
            } catch (e: Exception) {
                supportsBassBoost = false
            }
            try {
                virtualizer = Virtualizer(0, audioSessionId)
                supportsVirtualizer = virtualizer?.strengthSupported == true
            } catch (e: Exception) {
                supportsVirtualizer = false
            }
            try {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                supportsLoudnessEnhancer = true
            } catch (e: Exception) {
                supportsLoudnessEnhancer = false
            }
        }
        applySavedEffects(context)
    }

    @Synchronized
    fun applySavedEffects(context: Context) {
        val snap = loadSnapshot(context)
        equalizer?.let { eq ->
            runCatching {
                eq.enabled = snap.enabled
                if (snap.enabled) {
                    val bandCount = eq.numberOfBands.toInt()
                    val minMb = eq.bandLevelRange[0].toInt()
                    val maxMb = eq.bandLevelRange[1].toInt()
                    val resampled = resampleBands(snap.customBandsDb.toFloatArray(), bandCount)
                    for (i in 0 until bandCount) {
                        val db = (resampled[i] + snap.preAmpDb).coerceIn(-15f, 15f)
                        val mb = (db * 100f).toInt().coerceIn(minMb, maxMb).toShort()
                        eq.setBandLevel(i.toShort(), mb)
                    }
                }
            }
        }
        bassBoost?.let { bb ->
            runCatching {
                bb.enabled = snap.enabled && snap.bassBoostStrength > 0
                if (bb.enabled && bb.strengthSupported) {
                    bb.setStrength(snap.bassBoostStrength.coerceIn(0, 1000).toShort())
                }
            }
        }
        virtualizer?.let { vz ->
            runCatching {
                vz.enabled = snap.enabled && snap.virtualizerStrength > 0
                if (vz.enabled && vz.strengthSupported) {
                    vz.setStrength(snap.virtualizerStrength.coerceIn(0, 1000).toShort())
                }
            }
        }
        loudnessEnhancer?.let { le ->
            runCatching {
                val boostActive = snap.enabled && snap.volumeBoostPercent > 100
                le.enabled = boostActive
                if (boostActive) {
                    // 100% -> 0 mB, 200% -> +1800 mB (safe ceiling to prevent harsh clipping)
                    val gainMb = ((snap.volumeBoostPercent - 100) * 18).coerceIn(0, 1800)
                    le.setTargetGain(gainMb)
                }
            }
        }
    }

    fun getDeviceBandFrequenciesHz(): List<Int> {
        val eq = equalizer ?: return listOf(60, 230, 910, 3600, 14000)
        return runCatching {
            val count = eq.numberOfBands.toInt()
            (0 until count).map { (eq.getCenterFreq(it.toShort()) / 1000).coerceAtLeast(20) }
        }.getOrDefault(listOf(60, 230, 910, 3600, 14000))
    }

    @Synchronized
    fun releaseAll() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudnessEnhancer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        activeSessionId = 0
    }
}
