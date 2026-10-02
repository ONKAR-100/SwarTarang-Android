package com.example.indicconformer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DeviceTransport"

sealed interface TransportState {
    data object Idle : TransportState
    data object Discovering : TransportState
    data class PeerFound(val name: String, val address: String) : TransportState
    data class Connecting(val details: String = "Connecting...") : TransportState
    data class Connected(val peerName: String, val address: String, val via: LinkType) : TransportState
    data class ConnectionLost(val reason: String) : TransportState
    data class Error(val message: String) : TransportState
}

enum class LinkType {
    WIFI_DIRECT,
    LOCAL_NETWORK
}

enum class TransportMode {
    HOTSPOT_WIFI,
    WIFI_DIRECT
}

/**
 * Owns the full device-to-device transport lifecycle.
 * Supports:
 * 1. HOTSPOT / LOCAL WI-FI MESH: UDP beaconing + Gateway auto-probe + NSD + TCP Socket.
 *    (Will NEVER interfere with or disconnect active Wi-Fi or Hotspot radios!)
 * 2. WI-FI DIRECT: Peer-to-peer radio connection for when no router/hotspot exists.
 */
class DeviceTransport(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = TcpMessageChannel(scope)
    private val wifiDirect = WifiDirectTransport(appContext, scope)
    private val localNetwork = LocalNetworkTransport(appContext, scope, channel)
    private val hotspotMesh = HotspotMeshTransport(appContext, scope, channel) { _state.value = it }

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incomingResults = MutableStateFlow<SpeechResult?>(null)
    val incomingResults: StateFlow<SpeechResult?> = _incomingResults.asStateFlow()

    private val seenMessageIds = ConcurrentHashMap.newKeySet<String>()

    var activeRoleIsSender = false
        private set
    var currentMode = TransportMode.HOTSPOT_WIFI
        private set

    init {
        // Wi-Fi Direct peer discovered callback
        wifiDirect.onPeer = { name, address ->
            if (currentMode == TransportMode.WIFI_DIRECT) {
                Log.d(TAG, "Wi-Fi Direct peer found: $name ($address)")
                _state.value = TransportState.PeerFound(name, address)
                wifiDirect.connectTo(address)
            }
        }

        // Wi-Fi Direct group formed callback
        wifiDirect.onGroupAddress = { address, isOwner ->
            if (currentMode == TransportMode.WIFI_DIRECT) {
                Log.d(TAG, "Wi-Fi Direct group formed: GO=${address.hostAddress}, isOwner=$isOwner")
                if (isOwner) {
                    scope.launch {
                        channel.startServer(LinkType.WIFI_DIRECT)
                        _state.value = TransportState.Connected("Nearby device (GO)", address.hostAddress ?: "local", LinkType.WIFI_DIRECT)
                    }
                } else {
                    scope.launch {
                        _state.value = TransportState.Connecting("Connecting to group owner ${address.hostAddress}...")
                        channel.connectWithRetry(address, TcpMessageChannel.DEFAULT_PORT, maxRetries = 6, delayMs = 600, via = LinkType.WIFI_DIRECT)
                    }
                }
            }
        }

        // TCP channel lifecycle callbacks
        channel.onConnected = { peerName, address, via ->
            Log.d(TAG, "TCP connected to $peerName at $address via $via")
            _state.value = TransportState.Connected(peerName, address, via)
        }

        channel.onDisconnected = { reason ->
            Log.d(TAG, "TCP connection lost: $reason")
            _state.value = TransportState.ConnectionLost(reason)
        }

        channel.onRawMessage = { rawJson ->
            val result = SpeechResult.fromJson(rawJson)
            if (result != null) {
                val isNew = seenMessageIds.add(result.messageId)
                if (isNew) {
                    Log.d(TAG, "Received SpeechResult: id=${result.messageId}, text=${result.text}")
                    _incomingResults.value = result
                } else {
                    Log.d(TAG, "Ignored duplicate SpeechResult: id=${result.messageId}")
                }
            } else {
                Log.w(TAG, "Failed to parse SpeechResult: $rawJson")
            }
        }
    }

    fun start(roleIsSender: Boolean, mode: TransportMode = TransportMode.HOTSPOT_WIFI) {
        activeRoleIsSender = roleIsSender
        currentMode = mode

        if (!hasRequiredPermissions()) {
            _state.value = TransportState.Error("Required permissions (Nearby Devices / Location) are not granted")
            return
        }

        _state.value = TransportState.Discovering
        Log.d(TAG, "Starting transport in mode $mode. Role: ${if (roleIsSender) "Sender" else "Receiver"}")

        // 1. Always start local TCP server
        scope.launch {
            channel.startServer(defaultVia = if (mode == TransportMode.HOTSPOT_WIFI) LinkType.LOCAL_NETWORK else LinkType.WIFI_DIRECT)
        }

        if (mode == TransportMode.HOTSPOT_WIFI) {
            // CRITICAL: Stop Wi-Fi Direct to prevent Android from dropping Wi-Fi / Hotspot!
            wifiDirect.stop()

            // Launch Hotspot Mesh (UDP Broadcast + Gateway Probe)
            hotspotMesh.start(roleIsSender, channel.port)

            // Also announce via DNS-SD NSD
            scope.launch {
                localNetwork.publish(channel.port)
                localNetwork.startDiscovery()
            }
        } else {
            // Pure Wi-Fi Direct mode (radio-to-radio without router or hotspot)
            hotspotMesh.stop()
            localNetwork.stop()

            if (roleIsSender) {
                wifiDirect.createGroup()
            } else {
                wifiDirect.startDiscovery()
            }
        }
    }

    /**
     * Direct TCP connection to a specific IP address (e.g. Hotspot gateway 192.168.43.1).
     */
    fun connectToIp(ipAddress: String, port: Int = TcpMessageChannel.DEFAULT_PORT) {
        scope.launch {
            val trimmed = ipAddress.trim()
            if (trimmed.isEmpty()) return@launch
            _state.value = TransportState.Connecting("Connecting to $trimmed:$port...")
            try {
                val addr = InetAddress.getByName(trimmed)
                channel.connectWithRetry(addr, port, maxRetries = 3, delayMs = 400, via = LinkType.LOCAL_NETWORK)
            } catch (e: Exception) {
                _state.value = TransportState.Error("Connection failed to $trimmed: ${e.message}")
            }
        }
    }

    fun getMyIp(): String = getLocalIpv4Address() ?: "127.0.0.1"

    fun getSuggestedPeerIp(): String {
        val gateway = getGatewayIpv4(appContext)
        val my = getMyIp()
        if (gateway != null && gateway != "0.0.0.0" && gateway != my) {
            return gateway
        }
        return if (my.startsWith("192.168.43.")) "192.168.43.1" else my
    }

    fun send(result: SpeechResult) {
        scope.launch {
            if (_state.value !is TransportState.Connected) {
                Log.w(TAG, "Cannot send: transport is not connected")
                return@launch
            }
            seenMessageIds.add(result.messageId)
            val jsonPayload = result.toJson()
            channel.send(jsonPayload)
        }
    }

    fun reconnect() {
        Log.d(TAG, "Reconnecting transport...")
        stop()
        start(activeRoleIsSender, currentMode)
    }

    fun stop() {
        Log.d(TAG, "Stopping transport")
        hotspotMesh.stop()
        wifiDirect.stop()
        localNetwork.stop()
        channel.stop()
        _state.value = TransportState.Idle
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun hasRequiredPermissions(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Handles persistent TCP socket communication with explicit framing:
 * Frame format: "MV1|<Base64(UTF-8 JSON)>\n"
 */
private class TcpMessageChannel(private val scope: CoroutineScope) {
    companion object {
        const val DEFAULT_PORT = 42821
        private const val FRAME_PREFIX = "MV1|"
    }

    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var acceptJob: Job? = null
    private var readJob: Job? = null
    private val writeMutex = Mutex()
    private val stopped = AtomicBoolean(false)

    var onConnected: ((String, String, LinkType) -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    var onRawMessage: ((String) -> Unit)? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false
    val port: Int get() = server?.localPort ?: DEFAULT_PORT

    fun startServer(defaultVia: LinkType = LinkType.LOCAL_NETWORK) {
        if (server != null && !server!!.isClosed) return
        stopped.set(false)
        try {
            server = ServerSocket(DEFAULT_PORT).apply {
                reuseAddress = true
            }
            Log.d(TAG, "TCP Server listening on port ${server?.localPort}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TCP Server: ${e.message}")
            return
        }

        acceptJob?.cancel()
        acceptJob = scope.launch {
            while (isActive && !stopped.get()) {
                try {
                    val incoming = server?.accept() ?: break
                    Log.d(TAG, "Incoming TCP socket accepted from ${incoming.inetAddress.hostAddress}")
                    attach(incoming, defaultVia)
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    suspend fun connectWithRetry(
        address: InetAddress,
        port: Int,
        maxRetries: Int = 5,
        delayMs: Long = 500,
        via: LinkType = LinkType.LOCAL_NETWORK
    ) {
        if (socket?.isConnected == true) return
        stopped.set(false)

        for (attempt in 1..maxRetries) {
            if (!scope.isActive || stopped.get()) return
            try {
                Log.d(TAG, "TCP connect attempt to $address:$port ($attempt/$maxRetries)")
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(address, port), 4000)
                attach(newSocket, via)
                return
            } catch (e: IOException) {
                Log.d(TAG, "TCP connect attempt $attempt failed: ${e.message}")
                if (attempt < maxRetries) {
                    delay(delayMs)
                }
            }
        }
        Log.e(TAG, "All TCP connect attempts to $address:$port failed")
        onDisconnected?.invoke("Could not reach peer at $address:$port")
    }

    suspend fun send(jsonPayload: String): Boolean = writeMutex.withLock {
        val currentWriter = writer ?: run {
            Log.w(TAG, "Cannot send: writer is null")
            return false
        }
        return try {
            val encoded = Base64.getEncoder().encodeToString(jsonPayload.toByteArray(StandardCharsets.UTF_8))
            currentWriter.write("$FRAME_PREFIX$encoded")
            currentWriter.newLine()
            currentWriter.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Send failed: ${e.message}")
            onDisconnected?.invoke("Send failed: ${e.message}")
            false
        }
    }

    private fun attach(newSocket: Socket, via: LinkType) {
        runCatching { socket?.close() }
        socket = newSocket
        try {
            newSocket.tcpNoDelay = true
            newSocket.keepAlive = true
            writer = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8))
        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize socket streams: ${e.message}")
            return
        }

        val remoteIp = newSocket.inetAddress?.hostAddress ?: "local"
        onConnected?.invoke("Nearby Device", remoteIp, via)

        readJob?.cancel()
        readJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8))
                while (isActive && !stopped.get()) {
                    val line = reader.readLine()
                    if (line == null) {
                        Log.d(TAG, "TCP stream reached EOF (peer disconnected)")
                        break
                    }
                    if (line.startsWith(FRAME_PREFIX)) {
                        val base64Payload = line.removePrefix(FRAME_PREFIX)
                        try {
                            val decodedBytes = Base64.getDecoder().decode(base64Payload)
                            val decodedJson = String(decodedBytes, StandardCharsets.UTF_8)
                            onRawMessage?.invoke(decodedJson)
                        } catch (e: Exception) {
                            Log.w(TAG, "Corrupt message frame dropped: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (!stopped.get()) {
                    Log.d(TAG, "TCP read error: ${e.message}")
                }
            } finally {
                if (!stopped.get()) {
                    onDisconnected?.invoke("Peer disconnected")
                }
            }
        }
    }

    fun stop() {
        stopped.set(true)
        acceptJob?.cancel()
        readJob?.cancel()
        runCatching { socket?.close() }
        runCatching { server?.close() }
        socket = null
        server = null
        writer = null
    }
}

/**
 * Zero-configuration Hotspot and Local Wi-Fi discovery using:
 * 1. UDP broadcast beaconing (port 42823)
 * 2. Hotspot Gateway auto-probe (e.g. 192.168.43.1)
 * Never interferes with active Wi-Fi or Hotspot radios!
 */
private class HotspotMeshTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val channel: TcpMessageChannel,
    private val onStateChange: (TransportState) -> Unit
) {
    companion object {
        const val UDP_PORT = 42823
        private const val BEACON_PREFIX = "MVB|"
    }

    private val localInstanceId = UUID.randomUUID().toString().take(6)
    private var listenJob: Job? = null
    private var beaconJob: Job? = null
    private var probeJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)

    fun start(roleIsSender: Boolean, tcpPort: Int) {
        if (isRunning.getAndSet(true)) return
        val roleStr = if (roleIsSender) "SENDER" else "RECEIVER"
        val myIp = getLocalIpv4Address() ?: "0.0.0.0"

        // 1. Listen for UDP broadcast beacons
        listenJob = scope.launch(Dispatchers.IO) {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(UDP_PORT))
                    broadcast = true
                }
                val buf = ByteArray(512)
                while (isActive && isRunning.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        udpSocket?.receive(packet)
                    } catch (_: IOException) {
                        break
                    }
                    val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).trim()
                    if (msg.startsWith(BEACON_PREFIX)) {
                        val parts = msg.split("|")
                        if (parts.size >= 5) {
                            val peerRole = parts[1]
                            val peerIp = parts[2]
                            val peerPort = parts[3].toIntOrNull() ?: TcpMessageChannel.DEFAULT_PORT
                            val peerInstanceId = parts[4]

                            if (peerInstanceId != localInstanceId && peerIp != myIp && peerIp != "0.0.0.0") {
                                Log.d(TAG, "HotspotMesh received beacon from $peerRole at $peerIp:$peerPort")
                                onStateChange(TransportState.PeerFound("Device ($peerRole)", peerIp))
                                if (!channel.isConnected) {
                                    scope.launch {
                                        channel.connectWithRetry(
                                            InetAddress.getByName(peerIp),
                                            peerPort,
                                            maxRetries = 2,
                                            delayMs = 300,
                                            via = LinkType.LOCAL_NETWORK
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "HotspotMesh listen exception: ${e.message}")
            }
        }

        // 2. Broadcast periodic UDP beacons
        beaconJob = scope.launch(Dispatchers.IO) {
            val sendSocket = try {
                DatagramSocket().apply { broadcast = true }
            } catch (_: Exception) { null }

            while (isActive && isRunning.get()) {
                if (!channel.isConnected) {
                    try {
                        val currentIp = getLocalIpv4Address() ?: myIp
                        val beaconData = "$BEACON_PREFIX$roleStr|$currentIp|$tcpPort|$localInstanceId".toByteArray(StandardCharsets.UTF_8)
                        
                        // Send to standard 255.255.255.255
                        val p1 = DatagramPacket(beaconData, beaconData.size, InetAddress.getByName("255.255.255.255"), UDP_PORT)
                        sendSocket?.send(p1)

                        // Also send to 192.168.43.255 (standard Android hotspot subnet)
                        val p2 = DatagramPacket(beaconData, beaconData.size, InetAddress.getByName("192.168.43.255"), UDP_PORT)
                        sendSocket?.send(p2)
                    } catch (_: Exception) {}
                }
                delay(1200)
            }
            runCatching { sendSocket?.close() }
        }

        // 3. Hotspot Gateway Auto-Probe
        probeJob = scope.launch(Dispatchers.IO) {
            delay(400)
            val gateway = getGatewayIpv4(context)
            if (gateway != null && gateway != "0.0.0.0" && gateway != myIp) {
                Log.d(TAG, "Hotspot gateway auto-probe targeting $gateway:42821")
                if (!channel.isConnected && isRunning.get()) {
                    channel.connectWithRetry(
                        InetAddress.getByName(gateway),
                        TcpMessageChannel.DEFAULT_PORT,
                        maxRetries = 4,
                        delayMs = 400,
                        via = LinkType.LOCAL_NETWORK
                    )
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        listenJob?.cancel()
        beaconJob?.cancel()
        probeJob?.cancel()
        runCatching { udpSocket?.close() }
        udpSocket = null
        listenJob = null
        beaconJob = null
        probeJob = null
    }
}

/**
 * Discovers and announces the MarathiVoice service on shared local Wi-Fi via mDNS / DNS-SD.
 */
private class LocalNetworkTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val channel: TcpMessageChannel
) {
    private val nsd: NsdManager? = try {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    } catch (_: Throwable) {
        null
    }
    private val localInstanceId = UUID.randomUUID().toString().take(6)
    private val myServiceName = "ST-$localInstanceId"
    private val serviceType = "_swartarang._tcp."
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun publish(port: Int) {
        val nsdManager = nsd ?: return
        val info = NsdServiceInfo().apply {
            serviceName = myServiceName
            serviceType = this@LocalNetworkTransport.serviceType
            this.port = port
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "NSD service registered: ${info.serviceName} on port ${info.port}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: code $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
    }

    fun startDiscovery() {
        val nsdManager = nsd ?: return
        if (discovery != null) return
        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.d(TAG, "NSD discovery started for $type")
            }
            override fun onDiscoveryStopped(type: String) {
                Log.d(TAG, "NSD discovery stopped")
            }
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.w(TAG, "NSD start discovery failed: code $errorCode")
                discovery = null
            }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                discovery = null
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                Log.d(TAG, "NSD service found: ${info.serviceName}")
                if (info.serviceName.contains(myServiceName) || info.serviceName.contains(localInstanceId)) {
                    return
                }
                if (info.serviceType.contains("swartarang") || info.serviceType.contains("marathivoice")) {
                    runCatching {
                        nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "NSD resolve failed: code $errorCode")
                            }
                            override fun onServiceResolved(service: NsdServiceInfo) {
                                if (service.host.isLoopbackAddress) return
                                Log.d(TAG, "NSD service resolved: ${service.host}:${service.port}")
                                scope.launch {
                                    channel.connectWithRetry(service.host, service.port, via = LinkType.LOCAL_NETWORK)
                                }
                            }
                        })
                    }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                Log.d(TAG, "NSD service lost: ${info.serviceName}")
            }
        }
        runCatching { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    fun stop() {
        val nsdManager = nsd
        if (nsdManager != null) {
            discovery?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
            registration?.let { runCatching { nsdManager.unregisterService(it) } }
        }
        discovery = null
        registration = null
    }
}

/**
 * Manages Wi-Fi Direct (P2P) discovery, group creation, and connection handling.
 */
private class WifiDirectTransport(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val manager: WifiP2pManager? = try {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    } catch (_: Throwable) {
        null
    }
    private val channel: WifiP2pManager.Channel? = try {
        manager?.initialize(context, context.mainLooper, null)
    } catch (_: Throwable) {
        null
    }
    private var receiver: BroadcastReceiver? = null

    var onPeer: ((String, String) -> Unit)? = null
    var onGroupAddress: ((InetAddress, Boolean) -> Unit)? = null

    fun createGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        registerReceiver()
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct group created successfully")
            }
            override fun onFailure(reason: Int) {
                Log.d(TAG, "Wi-Fi Direct createGroup failed ($reason), falling back to peer discovery")
                startDiscovery()
            }
        })
    }

    fun startDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return
        registerReceiver()
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct peer discovery started")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Wi-Fi Direct peer discovery failed: reason $reason")
            }
        })
    }

    fun connectTo(address: String) {
        val mgr = manager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
        }
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct connect initiation succeeded to $address")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Wi-Fi Direct connect initiation failed to $address: reason $reason")
            }
        })
    }

    private fun registerReceiver() {
        if (receiver != null || manager == null || channel == null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val mgr = manager ?: return
                val ch = channel ?: return
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> runCatching {
                        mgr.requestPeers(ch) { peers: WifiP2pDeviceList ->
                            peers.deviceList.firstOrNull()?.let { device: WifiP2pDevice ->
                                Log.d(TAG, "Wi-Fi Direct peer list updated: ${device.deviceName} (${device.deviceAddress})")
                                onPeer?.invoke(device.deviceName, device.deviceAddress)
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> runCatching {
                        mgr.requestConnectionInfo(ch) { info: WifiP2pInfo ->
                            if (info.groupFormed && info.groupOwnerAddress != null) {
                                Log.d(TAG, "Wi-Fi Direct connected: GO=${info.groupOwnerAddress.hostAddress}, isOwner=${info.isGroupOwner}")
                                onGroupAddress?.invoke(info.groupOwnerAddress, info.isGroupOwner)
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching { mgr.removeGroup(ch, null) }
        runCatching { mgr.stopPeerDiscovery(ch, null) }
    }
}

// -------------------------------------------------------------
// Network Utility Functions
// -------------------------------------------------------------

fun getLocalIpv4Address(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (intf in interfaces) {
            if (intf.isLoopback || !intf.isUp) continue
            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                        return ip
                    }
                }
            }
        }
    } catch (_: Throwable) {}
    return null
}

fun getGatewayIpv4(context: Context): String? {
    try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val dhcp = wm?.dhcpInfo
        if (dhcp != null && dhcp.gateway != 0) {
            val g = dhcp.gateway
            return "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
        }
    } catch (_: Throwable) {}
    return null
}
