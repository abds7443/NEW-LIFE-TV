package com.newlife.tv.net

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.newlife.tv.data.L
import com.newlife.tv.data.VpnConfig
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WireGuard VPN client. The user imports a standard WireGuard .conf (from their own server
 * or a VPN provider); this class only brings that tunnel up and down.
 */
object VpnManager {
    enum class State { OFF, CONNECTING, ON, ERROR }

    val state = MutableStateFlow(State.OFF)
    val message = MutableStateFlow("")
    val activeName = MutableStateFlow("")

    @Volatile private var backend: GoBackend? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = "newlifetv"
        override fun onStateChange(newState: Tunnel.State) {
            state.value = if (newState == Tunnel.State.UP) State.ON else State.OFF
        }
    }

    private fun backend(ctx: Context): GoBackend =
        backend ?: synchronized(this) { backend ?: GoBackend(ctx.applicationContext).also { backend = it } }

    /** Intent asking the user to allow the VPN (null when already allowed). */
    fun permissionIntent(ctx: Context): Intent? = VpnService.prepare(ctx)

    /** Checks that a pasted / imported text is a valid WireGuard configuration. */
    fun validate(text: String): String? = try {
        Config.parse(ByteArrayInputStream(text.toByteArray())); null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /** Adds "IncludedApplications = <this app>" so only NEW LIFE TV uses the tunnel. */
    private fun applyAppOnly(text: String, pkg: String, appOnly: Boolean): String {
        val lines = text.lines().filterNot {
            val k = it.substringBefore('=').trim().lowercase()
            k == "includedapplications" || k == "excludedapplications"
        }.toMutableList()
        if (appOnly) {
            val i = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
            if (i >= 0) lines.add(i + 1, "IncludedApplications = $pkg")
        }
        return lines.joinToString("\n")
    }

    suspend fun connect(ctx: Context, cfg: VpnConfig, appOnly: Boolean) = withContext(Dispatchers.IO) {
        state.value = State.CONNECTING
        message.value = ""
        try {
            val text = applyAppOnly(cfg.text, ctx.packageName, appOnly)
            val config = Config.parse(ByteArrayInputStream(text.toByteArray()))
            backend(ctx).setState(tunnel, Tunnel.State.UP, config)
            activeName.value = cfg.name
            state.value = State.ON
        } catch (e: Exception) {
            state.value = State.ERROR
            message.value = e.message ?: e.javaClass.simpleName
        }
    }

    suspend fun disconnect(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
        state.value = State.OFF
        activeName.value = ""
    }

    /** Public IP and country as seen by the internet (to confirm the VPN works). */
    suspend fun publicIp(): String = withContext(Dispatchers.IO) {
        val c = Http.client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        for (u in listOf("https://ipinfo.io/json", "https://api.ipify.org?format=json")) {
            try {
                val js = parseJson(Http.getString(u, mapOf("User-Agent" to "NewLifeTV"), c)).obj() ?: continue
                val ip = js.s("ip")
                val where = listOf(js.s("city"), js.s("country")).filter { it.isNotBlank() }.joinToString(", ")
                if (ip.isNotBlank()) return@withContext if (where.isBlank()) ip else "$ip  ($where)"
            } catch (_: IOException) {
            }
        }
        L.t("تعذر معرفة العنوان", "Could not get the address")
    }
}
