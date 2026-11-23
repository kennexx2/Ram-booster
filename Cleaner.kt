package com.ramboost.app

import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

data class RamInfo(val totalMb: Long, val availMb: Long) {
    val usedPercent: Int
        get() = if (totalMb == 0L) 0 else ((totalMb - availMb) * 100 / totalMb).toInt()
}

data class CleanResult(val killed: Int, val before: Long, val after: Long) {
    val freedMb: Long get() = (after - before).coerceAtLeast(0)
}

object Cleaner {
    private const val PREFS = "ramboost"
    private const val KEY_PROTECTED = "protected"
    private const val JOB_ID = 1

    // Mensageiros protegidos por padrão, para você não perder notificações
    private val DEFAULT_PROTECTED = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.google.android.apps.messaging"
    )

    fun ram(ctx: Context): RamInfo {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return RamInfo(mi.totalMem / 1048576, mi.availMem / 1048576)
    }

    /** Apps instalados pelo usuário (e apps de sistema atualizados), exceto o próprio RamBoost. */
    fun userApps(ctx: Context): List<ApplicationInfo> {
        return ctx.packageManager.getInstalledApplications(0).filter {
            val isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val updatedSystem = (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            (!isSystem || updatedSystem) && it.packageName != ctx.packageName
        }
    }

    /** Apps que nunca devem ser fechados: teclado, launcher, telefone, SMS, Play Services. */
    fun essentials(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val set = mutableSetOf(ctx.packageName, "com.android.systemui", "com.google.android.gms")

        val home = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )
        home?.activityInfo?.packageName?.let { set.add(it) }

        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.let { set.add(it) }

        try {
            Telephony.Sms.getDefaultSmsPackage(ctx)?.let { set.add(it) }
        } catch (_: Exception) {
        }
        try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
                ?.defaultDialerPackage?.let { set.add(it) }
        } catch (_: Exception) {
        }
        return set
    }

    fun protectedSet(ctx: Context): Set<String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.contains(KEY_PROTECTED)) {
            p.getStringSet(KEY_PROTECTED, emptySet())!!.toSet()
        } else {
            DEFAULT_PROTECTED
        }
    }

    fun saveProtected(ctx: Context, packages: Set<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PROTECTED, packages).apply()
    }

    /**
     * Encerra os processos em segundo plano de todos os apps não protegidos.
     * Ignora apps que já estão parados. Chame fora da thread principal (espera ~1,2 s
     * para medir a RAM livre de verdade antes e depois).
     */
    fun clean(ctx: Context): CleanResult {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = ram(ctx).availMb
        val skip = essentials(ctx) + protectedSet(ctx)

        var killed = 0
        for (app in userApps(ctx)) {
            if (app.packageName in skip) continue
            if ((app.flags and ApplicationInfo.FLAG_STOPPED) != 0) continue
            am.killBackgroundProcesses(app.packageName)
            killed++
        }
        Runtime.getRuntime().gc()
        Thread.sleep(1200)
        val after = ram(ctx).availMb
        return CleanResult(killed, before, after)
    }

    fun isAutoOn(ctx: Context): Boolean {
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        return js.getPendingJob(JOB_ID) != null
    }

    fun setAuto(ctx: Context, on: Boolean) {
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (on) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, AutoCleanJob::class.java))
                .setPeriodic(30 * 60 * 1000L)
                .setPersisted(true)
                .build()
            js.schedule(job)
        } else {
            js.cancel(JOB_ID)
        }
    }
}
