package com.m3rd.app

import android.content.Context

/**
 * مدير النسخة الاحترافية لتطبيق مانجو درايف (Mango Drive Pro - Unlimited Telegram Cloud).
 * جميع مزايا التخزين السحابي غير المحدود، تقسيم الملفات الأكبر من 2GB، والخزنة المشفرة AES-256 مفعلة مجاناً للأبد.
 */
object MangoLicenseManager {

    const val STORE_PURCHASE_URL = "https://apk3rade.mangoad.online/app/com.mangodrive.app"
    const val LITE_VAULT_MAX_ITEMS = Int.MAX_VALUE
    const val LITE_DAILY_AI_LIMIT = Int.MAX_VALUE

    fun isPro(ctx: Context): Boolean = true

    fun getEditionName(ctx: Context): String = "مانجو درايف Pro (سحابة غير محدودة للأبد)"

    fun getTierBadge(ctx: Context): String = "∞ PRO"

    fun setProLicensed(ctx: Context, enabled: Boolean, key: String = "") {}

    fun activateWithKey(ctx: Context, enteredKey: String): Boolean = true

    fun openStorePurchasePage(ctx: Context) {}

    fun getRemainingAiEraserRuns(ctx: Context): Int = Int.MAX_VALUE

    fun consumeAiEraserRun(ctx: Context): Boolean = true

    fun canAddToVault(ctx: Context, currentVaultSize: Int, incomingCount: Int): Boolean = true
}
