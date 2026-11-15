package tech.astrix.droidvm.data

import tech.astrix.droidvm.i18n.tr
import java.io.File

/** Validation of the user-editable network/IP settings of a VM (QEMU user-mode networking). */
object NetConfig {
    private val ipRe = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun ip(s: String): Long? {
        val m = ipRe.matchEntire(s.trim()) ?: return null
        var v = 0L
        for (g in m.groupValues.drop(1)) { val o = g.toInt(); if (o > 255) return null; v = (v shl 8) or o.toLong() }
        return v
    }

    /** Returns (network, prefix) or null. */
    fun cidr(s: String): Pair<Long, Int>? {
        val parts = s.trim().split("/")
        if (parts.size != 2) return null
        val base = ip(parts[0]) ?: return null
        val p = parts[1].toIntOrNull() ?: return null
        if (p !in 8..30) return null
        val mask = (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return (base and mask) to p
    }

    private fun inNet(a: Long, net: Pair<Long, Int>): Boolean {
        val mask = (0xFFFFFFFFL shl (32 - net.second)) and 0xFFFFFFFFL
        return (a and mask) == net.first && a != net.first && a != (net.first or (mask.inv() and 0xFFFFFFFFL))
    }

    /** TCP ports the VM will listen on (on [VmConfig.bindAddress]). */
    fun listenPorts(vm: VmConfig): List<Int> = buildList {
        if (vm.network) vm.portForwards.filter { it.proto == "tcp" }.forEach { add(it.hostPort) }
        listOfNotNull(vm.telnetSerialPort, vm.spicePort, vm.exposeVncPort, vm.websocketPort).forEach { add(it) }
        if (vm.vmLink == VmLinkMode.LISTEN) add(vm.vmLinkPort)
    }

    private val hostRe = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$""")
    private val domainRe = Regex("""^[A-Za-z0-9.-]{1,253}$""")
    private val v6Re = Regex("""^[0-9A-Fa-f:]{2,39}/(\d{1,3})$""")

    /** null when OK, otherwise a localized error message. */
    fun validate(vm: VmConfig): String? {
        if (vm.network) {
            val net = cidr(vm.netCidr) ?: return tr("Invalid guest network, expected e.g. 10.0.2.0/24 (prefix 8-30)")
            val gw = ip(vm.gatewayIp) ?: return tr("Invalid gateway IP")
            val guest = ip(vm.guestIp) ?: return tr("Invalid guest IP")
            val dns = ip(vm.dnsIp) ?: return tr("Invalid DNS IP")
            if (!inNet(gw, net) || !inNet(guest, net) || !inNet(dns, net)) return tr("Gateway, guest and DNS IPs must be inside the guest network")
            if (gw == guest || dns == guest) return tr("Guest IP must differ from the gateway and DNS IPs")
            if (vm.guestHostname.isNotBlank() && !hostRe.matches(vm.guestHostname)) return tr("Invalid guest hostname")
            if (vm.dnsSearch.isNotBlank() && vm.dnsSearch.split(',', ' ').filter { it.isNotBlank() }.any { !domainRe.matches(it) })
                return tr("Invalid DNS search domain")
            if (vm.ipv6 && vm.ipv6Prefix.isNotBlank()) {
                val m = v6Re.matchEntire(vm.ipv6Prefix.trim()) ?: return tr("Invalid IPv6 prefix, expected e.g. fd00::/64")
                if (m.groupValues[1].toInt() !in 8..64) return tr("Invalid IPv6 prefix, expected e.g. fd00::/64")
            }
            vm.tftpDir?.takeIf { it.isNotBlank() }?.let { if (!File(it).isDirectory) return tr("TFTP folder not found: {}").replace("{}", it) }
        }
        if (ip(vm.bindAddress) == null) return tr("Invalid listen address (use 127.0.0.1 or 0.0.0.0)")
        if (vm.vmLink == VmLinkMode.CONNECT && vm.vmLinkHost.isBlank()) return tr("Enter the address of the other VM")
        val all = (if (vm.network) vm.portForwards.map { it.hostPort } else emptyList()) + listenPorts(vm) +
            (if (vm.vmLink == VmLinkMode.CONNECT) listOf(vm.vmLinkPort) else emptyList())
        if (all.any { it !in 1024..65535 }) return tr("Ports must be between 1024 and 65535")
        val dupFw = vm.network && vm.portForwards.groupBy { it.proto to it.hostPort }.any { it.value.size > 1 }
        val lp = listenPorts(vm)
        if (dupFw || lp.size != lp.toSet().size) return tr("The same port is used twice")
        return null
    }

    /** Ports that are already taken by another app / VM on this device (checked right before start). */
    fun busyPorts(vm: VmConfig): List<Int> = listenPorts(vm).filter { port ->
        runCatching {
            java.net.ServerSocket().use { ss ->
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress(vm.bindAddress.ifBlank { "127.0.0.1" }, port))
            }
        }.isFailure
    }
}
