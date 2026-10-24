package com.qoeprobe.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.telephony.*
import java.util.Locale
import java.util.concurrent.Executors

/** Serving-cell radio + GPS snapshot. One instance per process. */
@SuppressLint("MissingPermission")
object Radio : LocationListener {
    val KEYS = listOf(
        "rat", "data_net", "operator", "mcc", "mnc", "tac", "cell_id", "pci", "arfcn", "band", "bw_khz",
        "rsrp", "rsrq", "sinr", "cqi", "ta", "rssi", "rscp", "ecno",
        "nr_pci", "nr_arfcn", "nr_ss_rsrp", "nr_ss_rsrq", "nr_ss_sinr",
        "lat", "lon", "acc_m", "speed_kmh"
    )
    private lateinit var app: Context
    @Volatile var loc: Location? = null
    @Volatile private var cells: List<CellInfo> = emptyList()
    private val ex = Executors.newSingleThreadExecutor()
    private var started = false
    private val tm: TelephonyManager get() = app.getSystemService(TelephonyManager::class.java)

    fun init(c: Context) { if (!::app.isInitialized) app = c.applicationContext }
    fun hasPerm() = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (started || !hasPerm()) return
        started = true
        val lm = app.getSystemService(LocationManager::class.java)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
            if (lm.allProviders.contains(p))
                try { lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper()) } catch (_: Exception) {}
        refresh()
    }

    override fun onLocationChanged(l: Location) {
        val cur = loc
        // Prefer GPS; accept network fix only if GPS is stale.
        if (cur == null || l.provider == LocationManager.GPS_PROVIDER ||
            cur.provider != LocationManager.GPS_PROVIDER || l.time - cur.time > 5000) loc = l
    }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    /** Ask the modem for fresh cell info (async); snapshot() uses the latest result. */
    fun refresh() {
        if (!::app.isInitialized || !hasPerm()) return
        try {
            tm.requestCellInfoUpdate(ex, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(list: MutableList<CellInfo>) { cells = list }
            })
        } catch (_: Exception) {}
        if (cells.isEmpty()) try { cells = tm.allCellInfo ?: emptyList() } catch (_: Exception) {}
    }

    private fun v(x: Int) = if (x == Int.MAX_VALUE || x == Int.MIN_VALUE) "" else x.toString()
    private fun vl(x: Long) = if (x == Long.MAX_VALUE || x == Long.MIN_VALUE) "" else x.toString()

    private fun nr(o: MutableMap<String, String>, id: CellIdentityNr?, s: CellSignalStrengthNr) {
        if (id != null) { o["nr_pci"] = v(id.pci); o["nr_arfcn"] = v(id.nrarfcn) }
        o["nr_ss_rsrp"] = v(s.ssRsrp); o["nr_ss_rsrq"] = v(s.ssRsrq); o["nr_ss_sinr"] = v(s.ssSinr)
    }

    fun snapshot(): Map<String, String> {
        val o = LinkedHashMap<String, String>()
        KEYS.forEach { o[it] = "" }
        if (!::app.isInitialized) return o
        val list = cells
        try { o["data_net"] = netName(tm.dataNetworkType) } catch (_: Exception) {}
        try { o["operator"] = tm.networkOperatorName ?: "" } catch (_: Exception) {}
        for (c in list.filter { it.isRegistered }) {
            val empty = o["rat"].isNullOrEmpty()
            when (c) {
                is CellInfoLte -> if (empty) {
                    val id = c.cellIdentity; val s = c.cellSignalStrength
                    o["rat"] = "LTE"; o["mcc"] = id.mccString ?: ""; o["mnc"] = id.mncString ?: ""
                    o["tac"] = v(id.tac); o["cell_id"] = v(id.ci); o["pci"] = v(id.pci); o["arfcn"] = v(id.earfcn)
                    o["bw_khz"] = v(id.bandwidth)
                    if (Build.VERSION.SDK_INT >= 30) o["band"] = id.bands.joinToString("/")
                    o["rsrp"] = v(s.rsrp); o["rsrq"] = v(s.rsrq); o["sinr"] = v(s.rssnr)
                    o["cqi"] = v(s.cqi); o["ta"] = v(s.timingAdvance); o["rssi"] = v(s.rssi)
                }
                is CellInfoNr -> {
                    val id = c.cellIdentity as CellIdentityNr; val s = c.cellSignalStrength as CellSignalStrengthNr
                    if (empty) {
                        o["rat"] = "NR"; o["mcc"] = id.mccString ?: ""; o["mnc"] = id.mncString ?: ""
                        o["tac"] = v(id.tac); o["cell_id"] = vl(id.nci); o["pci"] = v(id.pci); o["arfcn"] = v(id.nrarfcn)
                        if (Build.VERSION.SDK_INT >= 30) o["band"] = id.bands.joinToString("/")
                    }
                    nr(o, id, s)
                }
                is CellInfoWcdma -> if (empty) {
                    val id = c.cellIdentity; val s = c.cellSignalStrength
                    o["rat"] = "WCDMA"; o["mcc"] = id.mccString ?: ""; o["mnc"] = id.mncString ?: ""
                    o["tac"] = v(id.lac); o["cell_id"] = v(id.cid); o["pci"] = v(id.psc); o["arfcn"] = v(id.uarfcn)
                    o["rscp"] = v(s.dbm)
                    if (Build.VERSION.SDK_INT >= 30) o["ecno"] = v(s.ecNo)
                }
                is CellInfoGsm -> if (empty) {
                    val id = c.cellIdentity; val s = c.cellSignalStrength
                    o["rat"] = "GSM"; o["mcc"] = id.mccString ?: ""; o["mnc"] = id.mncString ?: ""
                    o["tac"] = v(id.lac); o["cell_id"] = v(id.cid); o["arfcn"] = v(id.arfcn); o["rssi"] = v(s.dbm)
                }
                else -> {}
            }
        }
        // 5G NSA: NR leg shows as an unregistered NR cell or in SignalStrength.
        if (o["rat"] == "LTE" && o["nr_ss_rsrp"].isNullOrEmpty()) {
            val n = list.filterIsInstance<CellInfoNr>().firstOrNull()
            if (n != null) nr(o, n.cellIdentity as CellIdentityNr, n.cellSignalStrength as CellSignalStrengthNr)
            else try {
                tm.signalStrength?.cellSignalStrengths?.filterIsInstance<CellSignalStrengthNr>()?.firstOrNull()?.let { nr(o, null, it) }
            } catch (_: Exception) {}
            if (!o["nr_ss_rsrp"].isNullOrEmpty()) o["rat"] = "LTE+NR"
        }
        loc?.let { l ->
            o["lat"] = String.format(Locale.US, "%.6f", l.latitude)
            o["lon"] = String.format(Locale.US, "%.6f", l.longitude)
            o["acc_m"] = d0(l.accuracy.toDouble())
            if (l.hasSpeed()) o["speed_kmh"] = d1(l.speed * 3.6)
        }
        return o
    }

    /** Compact neighbour list: L<pci>:<earfcn>:<rsrp>;N<pci>:<arfcn>:<ssrsrp>;... */
    fun neighbors(): String = cells.filter { !it.isRegistered }.take(10).mapNotNull { c ->
        when (c) {
            is CellInfoLte -> "L${v(c.cellIdentity.pci)}:${v(c.cellIdentity.earfcn)}:${v(c.cellSignalStrength.rsrp)}"
            is CellInfoNr -> {
                val id = c.cellIdentity as CellIdentityNr; val s = c.cellSignalStrength as CellSignalStrengthNr
                "N${v(id.pci)}:${v(id.nrarfcn)}:${v(s.ssRsrp)}"
            }
            is CellInfoWcdma -> "W${v(c.cellIdentity.psc)}:${v(c.cellIdentity.uarfcn)}:${v(c.cellSignalStrength.dbm)}"
            else -> null
        }
    }.joinToString(";")

    private fun netName(t: Int) = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "TYPE_$t"
    }
}
