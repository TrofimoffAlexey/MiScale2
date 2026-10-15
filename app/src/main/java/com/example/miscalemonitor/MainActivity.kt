package com.example.miscalemonitor

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.UUID
import kotlin.math.max

private const val HEIGHT_CM = 175.0
private const val AGE_YEARS = 51
private const val MALE = true
private val BODY_COMPOSITION_UUID = ParcelUuid(UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb"))

/** Xiaomi 13-byte 0x181B service-data advertisement. */
internal data class Measurement(val weightKg: Double, val impedanceOhms: Int?, val stable: Boolean, val finished: Boolean)

internal object ScaleDecoder {
    fun decode(bytes: ByteArray): Measurement? {
        if (bytes.size != 13) return null
        fun u8(i: Int) = bytes[i].toInt() and 255
        val flags = u8(0) or (u8(1) shl 8)
        val rawWeight = u8(11) or (u8(12) shl 8)
        val pounds = flags and (1 shl 7) != 0
        val catty = flags and (1 shl 9) != 0
        val weightKg = when {
            pounds -> rawWeight / 100.0 * 0.45359237
            catty -> rawWeight / 100.0 * 0.5
            else -> rawWeight / 200.0
        }
        if (!weightKg.isFinite() || weightKg !in 0.0..300.0) return null
        val impedanceRaw = u8(9) or (u8(10) shl 8)
        val impedance = if (flags and (1 shl 14) != 0 && impedanceRaw in 200..1500) impedanceRaw else null
        return Measurement(weightKg, impedance, flags and (1 shl 10) != 0, flags and (1 shl 8) != 0)
    }
}

/** Approximate single-frequency BIA estimate, NOT Xiaomi's proprietary algorithm. */
internal data class Composition(val bmi: Double, val fatPct: Double, val musclePct: Double, val waterPct: Double)
internal object CompositionEstimator {
    fun estimate(weightKg: Double, impedance: Int): Composition? {
        if (weightKg !in 20.0..250.0 || impedance !in 200..1500) return null
        val bmi = weightKg / (HEIGHT_CM / 100.0 * HEIGHT_CM / 100.0)
        // Empirical adult TBW equation: height in cm, resistance in ohms, weight in kg.
        // Not clinically validated for this device; results can differ from Zepp Life.
        val tbwLiters = 0.372 * HEIGHT_CM * HEIGHT_CM / impedance + (if (MALE) 3.05 else 0.0) + 0.142 * weightKg
        val leanKg = tbwLiters / 0.73
        val fatPct = ((weightKg - leanKg) / weightKg * 100.0).coerceIn(0.0, 75.0)
        val waterPct = (tbwLiters / weightKg * 100.0).coerceIn(0.0, 80.0)
        // Rough muscle proxy from lean mass less an assumed bone/other lean fraction.
        val musclePct = ((leanKg - max(2.0, weightKg * 0.055)) / weightKg * 100.0).coerceIn(0.0, 90.0)
        return Composition(bmi, fatPct, musclePct, waterPct)
    }
}

class MainActivity : Activity() {
    private var adapter: BluetoothAdapter? = null
    private var scanning = false
    private lateinit var status: TextView
    private lateinit var weight: TextView
    private lateinit var impedance: TextView
    private lateinit var bmi: TextView
    private lateinit var fat: TextView
    private lateinit var muscle: TextView
    private lateinit var water: TextView
    private lateinit var button: Button
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val payload = result.scanRecord?.getServiceData(BODY_COMPOSITION_UUID) ?: return
            val m = ScaleDecoder.decode(payload) ?: return
            runOnUiThread { display(m) }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { scanning = false; button.text = "Начать поиск"; status.text = "Ошибка BLE: $errorCode" }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 40, 36, 36); setBackgroundColor(Color.rgb(15, 24, 34)) }
        fun line(text: String, size: Float, color: Int = Color.WHITE): TextView = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); setPadding(0, 10, 0, 10); root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        line("Mi Scale Monitor", 26f)
        status = line("Ожидание запуска поиска", 15f, Color.rgb(130, 195, 245))
        weight = line("— кг", 44f)
        impedance = line("Импеданс: —", 19f)
        bmi = line("ИМТ: —", 19f)
        fat = line("Жир: —", 19f)
        muscle = line("Мышечная масса: —", 19f)
        water = line("Вода: —", 19f)
        line("Мужчина · 51 год · 175 см", 15f, Color.LTGRAY)
        line("Жир, мышцы и вода — приблизительные расчёты по импедансу, не медицинские измерения. Значения могут отличаться от Zepp Life.", 13f, Color.LTGRAY)
        button = Button(this).apply { text = "Начать поиск"; setOnClickListener { if (scanning) stopScan() else startScan() } }
        root.addView(button)
        val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)
        ensurePermissionsAndScan()
    }
    private fun ensurePermissionsAndScan() {
        val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(permission), 100)
        else startScan()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startScan()
        else status.text = "Разрешите Bluetooth-поиск в настройках приложения"
    }
    private fun startScan() {
        val a = adapter ?: run { status.text = "Bluetooth LE недоступен"; return }
        if (!a.isEnabled) { status.text = "Включите Bluetooth и нажмите «Начать поиск»"; return }
        try {
            a.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
                ?: run { status.text = "BLE-сканер недоступен"; return }
            scanning = true; button.text = "Остановить поиск"; status.text = "Поиск весов… Встаньте на весы"
        } catch (e: SecurityException) { status.text = "Нет разрешения Bluetooth" }
    }
    private fun stopScan() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
        scanning = false; button.text = "Начать поиск"; status.text = "Поиск остановлен"
    }
    private fun display(m: Measurement) {
        // Don't clear the last completed measurement when the scale reports empty load.
        if (m.finished || m.weightKg < 5.0) return
        status.text = if (m.stable) "Вес зафиксирован" else "Идёт измерение…"
        weight.text = String.format(Locale.getDefault(), "%.2f кг", m.weightKg)
        impedance.text = "Импеданс: ${m.impedanceOhms?.let { "$it Ω" } ?: "ожидание контакта"}"
        bmi.text = String.format(Locale.getDefault(), "ИМТ: %.1f", m.weightKg / 1.75 / 1.75)
        // Never reuse impedance from a different frame or a previous measurement.
        val c = if (m.stable && m.impedanceOhms != null) CompositionEstimator.estimate(m.weightKg, m.impedanceOhms) else null
        fat.text = c?.let { String.format(Locale.getDefault(), "Жир: %.1f %%", it.fatPct) } ?: "Жир: —"
        muscle.text = c?.let { String.format(Locale.getDefault(), "Мышечная масса: %.1f %%", it.musclePct) } ?: "Мышечная масса: —"
        water.text = c?.let { String.format(Locale.getDefault(), "Вода: %.1f %%", it.waterPct) } ?: "Вода: —"
    }
    override fun onDestroy() { if (scanning) stopScan(); super.onDestroy() }
}
