package org.bigmeco.nearbylab

/** Swift implements this port using the official NearbyConnections SDK. All calls on main. */
interface NearbyTransport {
    fun setObserver(observer: TransportObserver)

    fun host(name: String)

    fun discover(name: String)

    fun connect(endpointId: String)

    fun verify(endpointId: String, accept: Boolean)

    fun send(endpointId: String, message: String)

    fun disconnect(endpointId: String)

    fun stop()

    fun appendLog(entry: String)

    fun shareLog()
}

interface TransportObserver {
    fun onEvent(kind: String, endpointId: String, value: String)
}

object NearbyConfig {
    const val SERVICE_ID = "org.bigmeco.nearbylab.v1"
    const val PREFIX = "NearbyLab|"
}
