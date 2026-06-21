package com.itsme.amkush.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object SharedPrefs {
    private const val PREFS_NAME = "facegate_prefs"

    private const val KEY_ACTIVATION_TOKEN = "activation_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_TARGET_APP_NAME = "target_app_name"
    private const val KEY_STREAM_URL = "stream_url"
    private const val KEY_STREAM_TYPE = "stream_type"
    private const val KEY_IS_PAID = "is_paid"
    private const val KEY_IS_TRIAL = "is_trial"
    private const val KEY_TRIAL_EXPIRY = "trial_expiry"
    private const val KEY_DENY_LIST = "deny_list"
    private const val KEY_SPOOF_MODEL = "spoof_model"
    private const val KEY_SPOOF_BRAND = "spoof_brand"
    private const val KEY_SPOOF_MANUFACTURER = "spoof_manufacturer"
    private const val KEY_SPOOF_ANDROID = "spoof_android"
    private const val KEY_SPOOF_BUILD_ID = "spoof_build_id"
    private const val KEY_SPOOF_SECURITY_PATCH = "spoof_security_patch"
    private const val KEY_SPOOF_ACTIVE = "spoof_active"
    private const val KEY_SPOOF_DEVICE_ID = "spoof_device_id"
    private const val KEY_SPOOF_SERIAL = "spoof_serial"
    private const val KEY_LAST_USED_URL = "last_used_url"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ==================== ACTIVATION ====================

    fun getActivationToken(): String? {
        return prefs.getString(KEY_ACTIVATION_TOKEN, null)
    }

    fun setActivationToken(token: String?) {
        prefs.edit { putString(KEY_ACTIVATION_TOKEN, token) }
    }

    fun getDeviceId(): String? {
        return prefs.getString(KEY_DEVICE_ID, null)
    }

    fun setDeviceId(deviceId: String) {
        prefs.edit { putString(KEY_DEVICE_ID, deviceId) }
    }

    fun isPaid(): Boolean {
        return prefs.getBoolean(KEY_IS_PAID, false)
    }

    fun setPaid(isPaid: Boolean) {
        prefs.edit { putBoolean(KEY_IS_PAID, isPaid) }
    }

    fun isTrial(): Boolean {
        return prefs.getBoolean(KEY_IS_TRIAL, false)
    }

    fun setTrial(isTrial: Boolean) {
        prefs.edit { putBoolean(KEY_IS_TRIAL, isTrial) }
    }

    fun getTrialExpiry(): Long {
        return prefs.getLong(KEY_TRIAL_EXPIRY, 0)
    }

    fun setTrialExpiry(expiry: Long) {
        prefs.edit { putLong(KEY_TRIAL_EXPIRY, expiry) }
    }

    fun clearActivation() {
        prefs.edit {
            remove(KEY_ACTIVATION_TOKEN)
            remove(KEY_IS_PAID)
            remove(KEY_IS_TRIAL)
            remove(KEY_TRIAL_EXPIRY)
        }
    }

    // ==================== TARGET APP ====================

    fun getTargetPackage(): String? {
        return prefs.getString(KEY_TARGET_PACKAGE, null)
    }

    fun setTargetPackage(packageName: String?) {
        prefs.edit { putString(KEY_TARGET_PACKAGE, packageName) }
    }

    fun getTargetAppName(): String? {
        return prefs.getString(KEY_TARGET_APP_NAME, null)
    }

    fun setTargetAppName(appName: String?) {
        prefs.edit { putString(KEY_TARGET_APP_NAME, appName) }
    }

    fun clearTarget() {
        prefs.edit {
            remove(KEY_TARGET_PACKAGE)
            remove(KEY_TARGET_APP_NAME)
        }
    }

    // ==================== STREAM ====================

    fun getStreamUrl(): String? {
        return prefs.getString(KEY_STREAM_URL, null)
    }

    fun setStreamUrl(url: String?) {
        prefs.edit { putString(KEY_STREAM_URL, url) }
    }

    fun getStreamType(): String? {
        return prefs.getString(KEY_STREAM_TYPE, null)
    }

    fun setStreamType(type: String?) {
        prefs.edit { putString(KEY_STREAM_TYPE, type) }
    }

    fun getLastUsedUrl(): String? {
        return prefs.getString(KEY_LAST_USED_URL, null)
    }

    fun setLastUsedUrl(url: String?) {
        prefs.edit { putString(KEY_LAST_USED_URL, url) }
    }

    // ==================== DENY LIST ====================

    fun getDenyList(): Set<String> {
        return prefs.getStringSet(KEY_DENY_LIST, emptySet()) ?: emptySet()
    }

    fun setDenyList(denyList: Set<String>) {
        prefs.edit { putStringSet(KEY_DENY_LIST, denyList) }
    }

    fun addToDenyList(packageName: String) {
        val current = getDenyList().toMutableSet()
        current.add(packageName)
        setDenyList(current)
    }

    fun removeFromDenyList(packageName: String) {
        val current = getDenyList().toMutableSet()
        current.remove(packageName)
        setDenyList(current)
    }

    fun isDenied(packageName: String): Boolean {
        return getDenyList().contains(packageName)
    }

    // ==================== SPOOFING ====================

    fun getSpoofModel(): String? {
        return prefs.getString(KEY_SPOOF_MODEL, null)
    }

    fun setSpoofModel(model: String?) {
        prefs.edit { putString(KEY_SPOOF_MODEL, model) }
    }

    fun getSpoofBrand(): String? {
        return prefs.getString(KEY_SPOOF_BRAND, null)
    }

    fun setSpoofBrand(brand: String?) {
        prefs.edit { putString(KEY_SPOOF_BRAND, brand) }
    }

    fun getSpoofManufacturer(): String? {
        return prefs.getString(KEY_SPOOF_MANUFACTURER, null)
    }

    fun setSpoofManufacturer(manufacturer: String?) {
        prefs.edit { putString(KEY_SPOOF_MANUFACTURER, manufacturer) }
    }

    fun getSpoofAndroid(): String? {
        return prefs.getString(KEY_SPOOF_ANDROID, null)
    }

    fun setSpoofAndroid(androidVersion: String?) {
        prefs.edit { putString(KEY_SPOOF_ANDROID, androidVersion) }
    }

    fun getSpoofBuildId(): String? {
        return prefs.getString(KEY_SPOOF_BUILD_ID, null)
    }

    fun setSpoofBuildId(buildId: String?) {
        prefs.edit { putString(KEY_SPOOF_BUILD_ID, buildId) }
    }

    fun getSpoofSecurityPatch(): String? {
        return prefs.getString(KEY_SPOOF_SECURITY_PATCH, null)
    }

    fun setSpoofSecurityPatch(patch: String?) {
        prefs.edit { putString(KEY_SPOOF_SECURITY_PATCH, patch) }
    }

    fun isSpoofActive(): Boolean {
        return prefs.getBoolean(KEY_SPOOF_ACTIVE, false)
    }

    fun setSpoofActive(active: Boolean) {
        prefs.edit { putBoolean(KEY_SPOOF_ACTIVE, active) }
    }

    fun getSpoofDeviceId(): String? {
        return prefs.getString(KEY_SPOOF_DEVICE_ID, null)
    }

    fun setSpoofDeviceId(deviceId: String?) {
        prefs.edit { putString(KEY_SPOOF_DEVICE_ID, deviceId) }
    }

    fun getSpoofSerial(): String? {
        return prefs.getString(KEY_SPOOF_SERIAL, null)
    }

    fun setSpoofSerial(serial: String?) {
        prefs.edit { putString(KEY_SPOOF_SERIAL, serial) }
    }

    // ==================== CLEAR ALL ====================

    fun clearAll() {
        prefs.edit { clear() }
    }
}