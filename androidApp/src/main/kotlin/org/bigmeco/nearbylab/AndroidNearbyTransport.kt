package org.bigmeco.nearbylab

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.File
import java.time.Instant

class AndroidNearbyTransport(private val context: Context) : NearbyTransport {
    private val client = Nearby.getConnectionsClient(context)
    private var observer: TransportObserver? = null
    private var name = ""
    private val peers = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()
    private val file = File(context.filesDir, "logs/nearby-${System.currentTimeMillis()}.ndjson")

    override fun setObserver(observer: TransportObserver) {
        this.observer = observer
        report(
            "environment",
            "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}; Nearby 19.5.1; service=${NearbyConfig.SERVICE_ID}",
        )
    }

    private fun event(kind: String, id: String = "", value: String = "") {
        observer?.onEvent(kind, id, value)
    }

    fun report(kind: String, value: String) = event(kind, value = value)

    private fun error(operation: String, id: String = "", error: Exception) =
        event("error", id, "$operation: $error")

    private val payloadCallback =
        object : PayloadCallback() {
            override fun onPayloadReceived(id: String, payload: Payload) {
                if (payload.type == Payload.Type.BYTES)
                    payload.asBytes()?.let { event("bytes", id, it.toString(Charsets.UTF_8)) }
            }

            override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
                event(
                    if (update.status == PayloadTransferUpdate.Status.FAILURE) "error"
                    else "transfer",
                    id,
                    "id=${update.payloadId} status=${update.status} ${update.bytesTransferred}/${update.totalBytes}",
                )
            }
        }
    private val lifecycle =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
                if (!info.endpointName.startsWith(NearbyConfig.PREFIX)) {
                    client.rejectConnection(id)
                    return
                }
                val code =
                    try {
                        PairingCode.fromRawToken(info.rawAuthenticationToken)
                    } catch (e: Exception) {
                        client.rejectConnection(id)
                        error("verification_format", id, e)
                        return
                    }
                pending.add(id)
                event("name", id, info.endpointName)
                event(
                    "info",
                    id,
                    "verification format=Nearby C++ Base64URL uppercase prefix5; Android numeric format=${info.authenticationDigits}",
                )
                event("verify", id, code)
            }

            override fun onConnectionResult(id: String, resolution: ConnectionResolution) {
                pending.remove(id)
                if (resolution.status.isSuccess) {
                    peers.add(id)
                    event("connected", id)
                } else {
                    peers.remove(id)
                    event("rejected", id, resolution.status.toString())
                }
            }

            override fun onDisconnected(id: String) {
                peers.remove(id)
                pending.remove(id)
                event("disconnected", id)
            }
        }

    override fun host(name: String) {
        this.name = name
        client
            .startAdvertising(
                name,
                NearbyConfig.SERVICE_ID,
                lifecycle,
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
            )
            .addOnSuccessListener { event("info", value = "advertising_started") }
            .addOnFailureListener { error("advertise", error = it) }
    }

    override fun discover(name: String) {
        this.name = name
        client
            .startDiscovery(
                NearbyConfig.SERVICE_ID,
                object : EndpointDiscoveryCallback() {
                    override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
                        event("found", id, info.endpointName)
                    }

                    override fun onEndpointLost(id: String) {
                        event("lost", id)
                    }
                },
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
            )
            .addOnSuccessListener { event("info", value = "discovery_started") }
            .addOnFailureListener { error("discover", error = it) }
    }

    override fun connect(endpointId: String) {
        client.requestConnection(name, endpointId, lifecycle).addOnFailureListener {
            error("connect", endpointId, it)
            event("disconnected", endpointId)
        }
    }

    override fun verify(endpointId: String, accept: Boolean) {
        if (!pending.contains(endpointId)) return
        if (accept)
            client.acceptConnection(endpointId, payloadCallback).addOnFailureListener {
                error("accept", endpointId, it)
            }
        else
            client.rejectConnection(endpointId).addOnFailureListener {
                error("reject", endpointId, it)
            }
    }

    override fun send(endpointId: String, message: String) {
        client
            .sendPayload(endpointId, Payload.fromBytes(message.toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { error("send", endpointId, it) }
    }

    override fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
        peers.remove(endpointId)
        pending.remove(endpointId)
    }

    override fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        peers.clear()
        pending.clear()
    }

    override fun appendLog(entry: String) {
        try {
            file.parentFile?.mkdirs()
            if (file.length() < 10_000_000)
                file.appendText("{\"utc\":\"${Instant.now()}\",\"event\":$entry}\n")
        } catch (_: Exception) {
            /* In-memory log remains available. */
        }
    }

    override fun shareLog() {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("application/x-ndjson")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, "NearbyLab").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
