package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.isMultiPort
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxOutboundWireguardBean
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.applyDNSNetworkSettings
import moe.matsuri.nb4a.checkEmpty
import moe.matsuri.nb4a.makeSingBoxRule
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val TAG_MIXED = "mixed-in"
const val TAG_TRANS = "trans-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"

const val TAG_DNS_IN = "dns-in"
const val TAG_DNS_OUT = "dns-out"

const val LOCALHOST = "127.0.0.1"
const val LOCAL_DNS_SERVER = "underlying://0.0.0.0"

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val alerts: List<Pair<Int, String>>,
    val selectorGroupId: Long,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

fun mergeJSON(j: String, to: MutableMap<String, Any>) {
    if (j.isBlank()) return
    val m = gson.fromJson(j, to.javaClass)
    m.forEach { (k, v) ->
        if (v is Map<*, *> && to[k] is Map<*, *>) {
            val currentMap = (to[k] as Map<*, *>).toMutableMap()
            currentMap += v
            to[k] = currentMap
        } else {
            to[k] = v
        }
    }
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id, //
                mapOf(TAG_PROXY to listOf(proxy)), //
                mapOf(proxy.id to TAG_PROXY), //
                listOf(),
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val selectorNames = ArrayList<String>()
    val group = SagerDatabase.groupDao.getById(proxy.groupId)
    val optionsToMerge = proxy.requireBean().customConfigJson ?: ""

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal())
            }
            return beanList.asReversed()
        }
        return mutableListOf(this)
    }

    fun selectorName(name_: String): String {
        var name = name_
        var count = 0
        while (selectorNames.contains(name)) {
            count++
            name = "$name_-$count"
        }
        selectorNames.add(name)
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val frontProxy = group?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val landingProxy = group?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val uidListDNSRemote = mutableListOf<Int>()
    val uidListDNSDirect = mutableListOf<Int>()
    val domainListDNSRemote = mutableListOf<String>()
    val domainListDNSDirect = mutableListOf<String>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val domainListDNSBlock = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    var directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest && DataStore.ipv6Mode != IPv6Mode.ONLY
    val needSniff = DataStore.trafficSniffing
    val externalIndexMap = ArrayList<IndexEntity>()
    val requireTransproxy = if (forTest) false else DataStore.requireTransproxy
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode
    val resolveDestination = DataStore.resolveDestination
    val alerts = mutableListOf<Pair<Int, String>>()

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !resolveDestination && !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    return MyOptions().apply {
        if (!forTest && DataStore.enableClashAPI) experimental = ExperimentalOptions().apply {
            clash_api = ClashAPIOptions().apply {
                external_controller = "127.0.0.1:9090"
                external_ui = "../files/yacd"
                cache_file = "../cache/clash.db"
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            // TODO nb4a hosts?
//            hosts = DataStore.hosts.split("\n")
//                .filter { it.isNotBlank() }
//                .associate { it.substringBefore(" ") to it.substringAfter(" ") }
//                .toMutableMap()

            servers = mutableListOf()
            rules = mutableListOf()

            when (ipv6Mode) {
                IPv6Mode.DISABLE -> {
                    strategy = "ipv4_only"
                }
                IPv6Mode.ONLY -> {
                    strategy = "ipv6_only"
                }
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                stack = if (DataStore.tunImplementation == 1) "system" else "gvisor"
                sniff = needSniff
                endpoint_independent_nat = true
                domain_strategy = genDomainStrategy(false)
                when (ipv6Mode) {
                    IPv6Mode.DISABLE -> {
                        inet4_address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                    }
                    IPv6Mode.ONLY -> {
                        inet6_address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    }
                    else -> {
                        inet4_address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                        inet6_address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    }
                }
            })
            inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
                domain_strategy = genDomainStrategy(false)
                if (needSniff) {
                    sniff = true
//                destOverride = when {
//                    useFakeDns && !trafficSniffing -> listOf("fakedns")
//                    useFakeDns -> listOf("fakedns", "http", "tls", "quic")
//                    else -> listOf("http", "tls", "quic")
//                }
//                metadataOnly = useFakeDns && !trafficSniffing
//                routeOnly = true
                }
            })
        }

        if (requireTransproxy) {
            if (DataStore.transproxyMode == 1) {
                inbounds.add(Inbound_TProxyOptions().apply {
                    type = "tproxy"
                    tag = TAG_TRANS
                    listen = bind
                    listen_port = DataStore.transproxyPort
                    sniff = needSniff
                    domain_strategy = genDomainStrategy(false)
                })
            } else {
                inbounds.add(Inbound_RedirectOptions().apply {
                    type = "redirect"
                    tag = TAG_TRANS
                    listen = bind
                    listen_port = DataStore.transproxyPort
                    sniff = needSniff
                    domain_strategy = genDomainStrategy(false)
                })
            }
        }

        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            rules = mutableListOf()
        }

        // returns outbound tag
        fun buildChain(
            chainId: Long, entity: ProxyEntity
        ): String {
            val profileList = entity.resolveChain()
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound = mutableMapOf<String, Any>()
            lateinit var pastOutbound: MutableMap<String, Any>
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<MutableMap<String, Any>>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            var currentDomainStrategy = genDomainStrategy(false)

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()
                }

                if (needGlobal) {
                    globalOutbounds[proxyEntity.id]?.let {
                        if (index == 0) chainTagOut = it // single, duplicate chain
                        return@forEachIndexed
                    }
                }

                // last profile set as "proxy"
                if (chainId == 0L && index == 0) {
                    tagOut = TAG_PROXY
                }

                // selector human readable name
                if (buildSelector && index == 0) {
                    tagOut = selectorName(bean.displayName())
                }

                // now tagOut is determined
                if (needGlobal) {
                    globalOutbounds[proxyEntity.id] = tagOut
                }

                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound["detour"] = tagOut
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // Chain outbound
                if (proxyEntity.needExternal()) {
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }.asMap()
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean ->
                            gson.fromJson(bean.config, currentOutbound.javaClass)
                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean).asMap()
                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean).asMap()
                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean).asMap()
                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean).asMap()
                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean).asMap()
                        is WireGuardBean ->
                            buildSingBoxOutboundWireguardBean(bean).asMap()
                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean).asMap()
                        else -> throw IllegalStateException("can't reach")
                    }

                    currentOutbound.apply {
                        // TODO nb4a keepAliveInterval?
//                        val keepAliveInterval = DataStore.tcpKeepAliveInterval
//                        val needKeepAliveInterval = keepAliveInterval !in intArrayOf(0, 15)

                        if (!muxApplied && proxyEntity.needCoreMux()) {
                            muxApplied = true
                            currentOutbound["multiplex"] = MultiplexOptions().apply {
                                enabled = true
                                max_streams = DataStore.muxConcurrency
                            }
                        }
                    }

                    // custom JSON merge
                    if (bean.customOutboundJson.isNotBlank()) {
                        mergeJSON(bean.customOutboundJson, currentOutbound)
                    }
                }

                pastEntity?.requireBean()?.apply {
                    // don't loopback
                    if (currentDomainStrategy != "" && !serverAddress.isIpAddress()) {
                        domainListDNSDirectForce.add("full:$serverAddress")
                    }
                }
                if (forTest) {
                    currentDomainStrategy = ""
                }

                currentOutbound["tag"] = tagOut
                currentOutbound["domain_strategy"] = currentDomainStrategy

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> "hysteria-plugin"
                            is TuicBean -> "tuic-plugin"
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (bean is HysteriaBean) {
                            throw Exception("not supported hysteria-plugin (SagerNet)")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                            pastInboundTag = tag

                            // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
                    }
                }

                outbounds.add(currentOutbound)
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // build outbounds
        if (buildSelector) {
            val list = group?.id?.let { SagerDatabase.proxyDao.getByGroup(it) }
            list?.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = tagMap[proxy.id]
                outbounds = tagMap.values.toList()
            }.asMap())
        } else {
            buildChain(0, proxy)
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }

        // apply user rules
        for (rule in extraRules) {
            if (rule.packages.isNotEmpty()) {
                PackageCache.awaitLoadSync()
            }
            val uidList2 = rule.packages.map {
                if (!isVPN) {
                    alerts.add(0 to rule.displayName())
                }
                PackageCache[it]?.takeIf { uid -> uid >= 1000 }
            }.toHashSet().filterNotNull()

            val ruleObj = Rule_DefaultOptions().apply {
                if (uidList2.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                    user_id = uidList2
                }
                var domainList2: List<String>? = null
                if (rule.domains.isNotBlank()) {
                    domainList2 = rule.domains.split("\n")
                    makeSingBoxRule(domainList2, false)
                }
                if (rule.ip.isNotBlank()) {
                    makeSingBoxRule(rule.ip.split("\n"), true)
                }
                if (rule.port.isNotBlank()) {
                    port = mutableListOf<Int>()
                    port_range = mutableListOf<String>()
                    rule.port.split(",").map {
                        if (it.contains(":")) {
                            port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { port.add(this) }
                        }
                    }
                }
                if (rule.sourcePort.isNotBlank()) {
                    source_port = mutableListOf<Int>()
                    source_port_range = mutableListOf<String>()
                    rule.sourcePort.split(",").map {
                        if (it.contains(":")) {
                            source_port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { source_port.add(this) }
                        }
                    }
                }
                if (rule.network.isNotBlank()) {
                    network = rule.network
                }
                if (rule.source.isNotBlank()) {
                    source_ip_cidr = rule.source.split("\n")
                }
                if (rule.protocol.isNotBlank()) {
                    protocol = rule.protocol.split("\n")
                }

                // also bypass lookup
                // cannot use other outbound profile to lookup...
                if (rule.outbound == -1L) {
                    uidListDNSDirect += uidList2
                    if (domainList2 != null) domainListDNSDirect += domainList2
                } else if (rule.outbound == 0L) {
                    uidListDNSRemote += uidList2
                    if (domainList2 != null) domainListDNSRemote += domainList2
                } else if (rule.outbound == -2L) {
                    if (domainList2 != null) domainListDNSBlock += domainList2
                }

                outbound = when (val outId = rule.outbound) {
                    0L -> TAG_PROXY
                    -1L -> TAG_BYPASS
                    -2L -> TAG_BLOCK
                    else -> if (outId == proxy.id) TAG_PROXY else tagMap[outId]
                        ?: throw Exception("invalid rule")
                }
            }

            if (!ruleObj.checkEmpty()) {
                route.rules.add(ruleObj)
            }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(Outbound().apply {
            tag = freedom
            type = "direct"
        }.asMap())

        outbounds.add(Outbound().apply {
            tag = TAG_BLOCK
            type = "block"
        }.asMap())

        if (!forTest) {
            inbounds.add(0, Inbound_DirectOptions().apply {
                type = "direct"
                tag = TAG_DNS_IN
                listen = bind
                listen_port = DataStore.localDNSPort
                override_address = "8.8.8.8"
                override_port = 53
            })

            outbounds.add(Outbound().apply {
                type = "dns"
                tag = TAG_DNS_OUT
            }.asMap())
        }

        if (DataStore.directDnsUseSystem) {
            // finally able to use "localDns" now...
            directDNS = listOf(LOCAL_DNS_SERVER)
        }

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress
            if (it is HysteriaBean && it.isMultiPort()) {
                serverAddr = it.serverAddress.substringBeforeLast(":")
            }
            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        // remote dns obj
        remoteDns.firstOrNull().let {
            dns.servers.add(DNSServerOptions().apply {
                address = it ?: throw Exception("No remote DNS, check your settings!")
                tag = "dns-remote"
                address_resolver = "dns-direct"
                applyDNSNetworkSettings(false)
            })
        }

        // add directDNS objects here
        directDNS.firstOrNull().let {
            dns.servers.add(DNSServerOptions().apply {
                address = it ?: throw Exception("No direct DNS, check your settings!")
                tag = "dns-direct"
                detour = "direct"
                address_resolver = "dns-local"
                applyDNSNetworkSettings(true)
            })
        }
        dns.servers.add(DNSServerOptions().apply {
            address = LOCAL_DNS_SERVER
            tag = "dns-local"
            detour = "direct"
        })
        dns.servers.add(DNSServerOptions().apply {
            address = "rcode://success"
            tag = "dns-block"
        })

        // dns object user rules
        if (enableDnsRouting) {
            val dnsRuleObj = mutableListOf<DNSRule_DefaultOptions>()
            if (uidListDNSRemote.isNotEmpty()) {
                if (useFakeDns) dnsRuleObj.add(
                    DNSRule_DefaultOptions().apply {
                        user_id = uidListDNSRemote.toHashSet().toList()
                        server = "dns-fake"
                        inbound = listOf("tun-in")
                    }
                )
                dnsRuleObj.add(
                    DNSRule_DefaultOptions().apply {
                        user_id = uidListDNSRemote.toHashSet().toList()
                        server = "dns-remote"
                    }
                )
            }
            if (domainListDNSRemote.isNotEmpty()) {
                if (useFakeDns) dnsRuleObj.add(
                    DNSRule_DefaultOptions().apply {
                        makeSingBoxRule(domainListDNSRemote.toHashSet().toList())
                        server = "dns-fake"
                        inbound = listOf("tun-in")
                    }
                )
                dnsRuleObj.add(
                    DNSRule_DefaultOptions().apply {
                        makeSingBoxRule(domainListDNSRemote.toHashSet().toList())
                        server = "dns-remote"
                    }
                )
            }
            if (uidListDNSDirect.isNotEmpty()) {
                dnsRuleObj.add(
                    DNSRule_DefaultOptions().apply {
                        user_id = uidListDNSDirect.toHashSet().toList()
                        server = "dns-direct"
                    }
                )
            }
            if (domainListDNSDirect.isNotEmpty()) {
                dnsRuleObj.add(
                    DNSRule_DefaultOptions().apply {
                        makeSingBoxRule(domainListDNSDirect.toHashSet().toList())
                        server = "dns-direct"
                    }
                )
            }
            if (domainListDNSBlock.isNotEmpty()) {
                dnsRuleObj.add(
                    DNSRule_DefaultOptions().apply {
                        makeSingBoxRule(domainListDNSBlock.toHashSet().toList())
                        server = "dns-block"
                        disable_cache = true
                    }
                )
            }
            dnsRuleObj.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            // Disable DNS for test
            dns.rules.clear()
        } else {
            // built-in DNS rules
            route.rules.add(0, Rule_DefaultOptions().apply {
                inbound = listOf(TAG_DNS_IN)
                outbound = TAG_DNS_OUT
            })
            route.rules.add(0, Rule_DefaultOptions().apply {
                port = listOf(53)
                outbound = TAG_DNS_OUT
            }) // TODO new mode use system dns?
            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    geoip = listOf("private")
                })
            }
            // block mcast
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                outbound = TAG_BLOCK
            })
            dns.rules.add(DNSRule_DefaultOptions().apply {
                domain_suffix = listOf(".arpa.", ".arpa")
                server = "dns-block"
                disable_cache = true
            })
            // force bypass
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                })
            }
        }

        // fakedns obj
        if (useFakeDns) {
            dns.servers.add(DNSServerOptions().apply {
                address = "fakedns://" + VpnService.FAKEDNS_VLAN4_CLIENT + "/15"
                tag = "dns-fake"
                strategy = "ipv4_only"
            })
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                auth_user = listOf("fakedns")
                server = "dns-remote"
            })
            dns.rules.add(DNSRule_DefaultOptions().apply {
                inbound = listOf("tun-in")
                server = "dns-fake"
                disable_cache = true
            })
        }
    }.let {
        ConfigBuildResult(
            gson.toJson(it.asMap().apply {
                mergeJSON(optionsToMerge, this)
            }),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            alerts,
            if (buildSelector) group!!.id else -1L
        )
    }

}
