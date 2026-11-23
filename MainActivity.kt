package com.ramboost.app

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val BG = Color.parseColor("#0F1115")
    private val CARD = Color.parseColor("#1A1D24")
    private val GREEN = Color.parseColor("#2ECC71")
    private val TXT = Color.WHITE
    private val SUB = Color.parseColor("#9AA3B2")
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private lateinit var ramText: TextView
    private lateinit var ramBar: ProgressBar
    private lateinit var status: TextView
    private lateinit var cleanBtn: Button
    private lateinit var tipsBox: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radiusDp: Int = 16) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = s
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }
        scroll.addView(root)

        root.addView(text("RamBoost", 28f, TXT, true))

        ramText = text("", 16f, SUB)
        ramText.setPadding(0, dp(12), 0, dp(6))
        root.addView(ramText)

        ramBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(GREEN)
        }
        root.addView(ramBar, LinearLayout.LayoutParams(MATCH, dp(10)))

        cleanBtn = Button(this).apply {
            text = "LIMPAR RAM"
            setTextColor(Color.BLACK)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(GREEN)
            }
            stateListAnimator = null
        }
        root.addView(cleanBtn, LinearLayout.LayoutParams(dp(180), dp(180)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(24)
            bottomMargin = dp(16)
        })

        status = text("Toque para liberar memória.", 15f, SUB).apply { gravity = Gravity.CENTER }
        root.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))

        cleanBtn.setOnClickListener { runClean() }

        // Limpeza automática periódica
        val autoSwitch = Switch(this).apply {
            text = "Limpeza automática (a cada 30 min)"
            setTextColor(TXT)
            isChecked = Cleaner.isAutoOn(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                try {
                    Cleaner.setAuto(this@MainActivity, on)
                } catch (e: Exception) {
                    toast("Não foi possível agendar a limpeza automática")
                }
            }
        }
        root.addView(autoSwitch, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(24) })

        val protectBtn = Button(this).apply {
            text = "Escolher apps protegidos"
            setOnClickListener { chooseProtected() }
        }
        root.addView(protectBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        root.addView(text("Otimizações sugeridas", 20f, TXT, true).apply {
            setPadding(0, dp(28), 0, dp(4))
        })
        tipsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tipsBox)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshRam()
        refreshTips()
    }

    // ---------------------------------------------------------------- RAM

    private fun refreshRam() {
        val r = Cleaner.ram(this)
        ramText.text = "RAM em uso: ${r.totalMb - r.availMb} MB de ${r.totalMb} MB (${r.usedPercent}%)"
        ramBar.progress = r.usedPercent
    }

    private fun runClean() {
        cleanBtn.isEnabled = false
        status.text = "Limpando..."
        Thread {
            val r = try {
                Cleaner.clean(this)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                cleanBtn.isEnabled = true
                status.text = if (r == null) {
                    "Não foi possível limpar agora."
                } else {
                    "${r.killed} apps encerrados em segundo plano\n" +
                        "RAM livre: ${r.before} MB → ${r.after} MB (+${r.freedMb} MB)"
                }
                refreshRam()
            }
        }.start()
    }

    private fun chooseProtected() {
        val ess = Cleaner.essentials(this)
        val pm = packageManager
        val apps = Cleaner.userApps(this)
            .filter { it.packageName !in ess }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        val names = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val prot = Cleaner.protectedSet(this)
        val checked = apps.map { it.packageName in prot }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Apps protegidos (não serão fechados)")
            .setMultiChoiceItems(names, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Salvar") { _, _ ->
                val chosen = apps.filterIndexed { i, _ -> checked[i] }.map { it.packageName }.toSet()
                Cleaner.saveProtected(this, chosen)
                toast("Salvo")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------- Dicas

    private fun addTip(
        title: String,
        desc: String,
        label: String? = null,
        action: (() -> Unit)? = null
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(CARD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(text(title, 16f, TXT, true))
        card.addView(text(desc, 14f, SUB).apply { setPadding(0, dp(4), 0, 0) })
        if (label != null && action != null) {
            card.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }
        tipsBox.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
    }

    private fun refreshTips() {
        tipsBox.removeAllViews()

        // 1. Animações
        val scale = try {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        } catch (e: Exception) {
            1f
        }
        if (scale > 0.5f) {
            addTip(
                "Reduzir animações",
                "Deixa o sistema mais rápido e gasta menos processamento.",
                "Aplicar"
            ) { applyAnimations() }
        }

        // 2. Armazenamento
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            val freePct = (st.availableBytes * 100 / st.totalBytes).toInt()
            if (freePct < 20) {
                addTip(
                    "Armazenamento quase cheio ($freePct% livre)",
                    "Pouco espaço livre deixa o celular lento. Apague arquivos e apps que você não usa.",
                    "Abrir armazenamento"
                ) { open(Settings.ACTION_INTERNAL_STORAGE_SETTINGS) }
            }
        } catch (_: Exception) {
        }

        // 3. Apps sem uso
        if (hasUsageAccess()) {
            val unused = unusedApps()
            if (unused.isNotEmpty()) {
                addTip(
                    "${unused.size} apps sem uso há 30+ dias",
                    "Apps parados ocupam espaço e podem rodar em segundo plano.",
                    "Ver e desinstalar"
                ) { showUnused(unused) }
            }
        } else {
            addTip(
                "Encontrar apps que você não usa",
                "Ative o acesso de uso para o RamBoost listar apps parados há mais de 30 dias.",
                "Ativar acesso"
            ) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        }

        // 4. Limite de processos em segundo plano
        addTip(
            "Limitar processos em segundo plano",
            "Em Opções do desenvolvedor, use \"Limite de processos em segundo plano\" = no máximo 2 processos. " +
                "É uma das melhores formas de economizar RAM. Se o menu não aparecer: Configurações > " +
                "Sobre o telefone > toque 7 vezes em Número da versão.",
            "Abrir opções do desenvolvedor"
        ) { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }

        // 5. Dicas gerais para pouca RAM
        val totalMb = Cleaner.ram(this).totalMb
        if (totalMb <= 4096) {
            addTip(
                "Use versões Lite dos apps",
                "Facebook Lite, Messenger Lite, Instagram Lite, Google Go e similares usam bem menos RAM."
            )
        }
        addTip(
            "Remova widgets e papel de parede animado",
            "Cada widget e papel de parede animado mantém processos ativos o tempo todo."
        )
    }

    private fun open(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            toast("Não foi possível abrir essa tela neste aparelho")
        }
    }

    private fun applyAnimations() {
        try {
            Settings.Global.putFloat(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 0.5f)
            Settings.Global.putFloat(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 0.5f)
            Settings.Global.putFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f)
            toast("Animações reduzidas")
            refreshTips()
        } catch (e: SecurityException) {
            AlertDialog.Builder(this)
                .setTitle("Permissão necessária")
                .setMessage(
                    "O Android só permite isso com uma permissão especial. Conecte o celular ao " +
                        "computador uma vez (depuração USB ligada) e rode:\n\n" +
                        "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n\n" +
                        "Ou faça manualmente: Opções do desenvolvedor > Escala de animação > 0,5x."
                )
                .setPositiveButton("Abrir opções do desenvolvedor") { _, _ ->
                    open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                }
                .setNegativeButton("Fechar", null)
                .show()
        }
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun unusedApps(): List<Pair<String, String>> {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val limit = now - 30L * 86_400_000L
        val lastUsed = HashMap<String, Long>()
        usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, limit, now).forEach {
            if (it.lastTimeUsed > (lastUsed[it.packageName] ?: 0L)) {
                lastUsed[it.packageName] = it.lastTimeUsed
            }
        }
        val ess = Cleaner.essentials(this)
        val pm = packageManager
        return pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName !in ess }
            .filter { (lastUsed[it.packageName] ?: 0L) < limit }
            .filter {
                try {
                    pm.getPackageInfo(it.packageName, 0).firstInstallTime < limit
                } catch (e: Exception) {
                    false
                }
            }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
    }

    private fun showUnused(list: List<Pair<String, String>>) {
        AlertDialog.Builder(this)
            .setTitle("Sem uso há 30+ dias — toque para desinstalar")
            .setItems(list.map { it.second }.toTypedArray()) { _, i ->
                try {
                    startActivity(
                        Intent(Intent.ACTION_DELETE, Uri.parse("package:${list[i].first}"))
                    )
                } catch (e: ActivityNotFoundException) {
                    toast("Não foi possível abrir a desinstalação")
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }
}
