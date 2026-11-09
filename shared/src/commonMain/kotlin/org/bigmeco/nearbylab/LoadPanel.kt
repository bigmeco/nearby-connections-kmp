package org.bigmeco.nearbylab

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.bigmeco.nearbylab.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LoadPanel(state: LabState, onIntent: (LabIntent) -> Unit) {
    val focus = LocalFocusManager.current
    val numberOptions =
        KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
    val done = KeyboardActions(onDone = { focus.clearFocus() })
    var size by rememberSaveable { mutableStateOf("1024") }
    var rate by rememberSaveable { mutableStateOf("10") }
    var seconds by rememberSaveable { mutableStateOf("10") }
    var window by rememberSaveable { mutableStateOf("16") }
    var mode by rememberSaveable { mutableStateOf(LoadMode.ECHO) }
    val plan =
        LoadPlan(
            mode,
            size.toIntOrNull() ?: 0,
            rate.toIntOrNull() ?: -1,
            seconds.toIntOrNull() ?: 0,
            window.toIntOrNull() ?: 0,
        )
    val valid = runCatching { plan.validate() }.isSuccess
    val load = state.load
    val connected = state.peers.filter { it.status == PeerStatus.CONNECTED }
    val canStart = connected.isNotEmpty() && valid && !load.running && load.remotePending == 0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.load_title), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                mode == LoadMode.ECHO,
                {
                    focus.clearFocus()
                    mode = LoadMode.ECHO
                },
                label = { Text(stringResource(Res.string.load_echo)) },
            )
            FilterChip(
                mode == LoadMode.RELAY,
                {
                    focus.clearFocus()
                    mode = LoadMode.RELAY
                },
                label = { Text(stringResource(Res.string.load_relay)) },
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(64, 1024, 8192, 16384, 65536, 262144, 1048576).forEach { bytes ->
                TextButton(
                    {
                        focus.clearFocus()
                        size = bytes.toString()
                    },
                    enabled = !load.running,
                ) {
                    Text(bytes.toString())
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                size,
                { size = it.filter(Char::isDigit).take(8) },
                Modifier.weight(1f),
                label = { Text(stringResource(Res.string.load_size)) },
                enabled = !load.running,
                singleLine = true,
                keyboardOptions = numberOptions,
                keyboardActions = done,
            )
            OutlinedTextField(
                rate,
                { rate = it.filter(Char::isDigit).take(5) },
                Modifier.weight(1f),
                label = { Text(stringResource(Res.string.load_rate)) },
                enabled = !load.running,
                singleLine = true,
                keyboardOptions = numberOptions,
                keyboardActions = done,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                seconds,
                { seconds = it.filter(Char::isDigit).take(4) },
                Modifier.weight(1f),
                label = { Text(stringResource(Res.string.load_seconds)) },
                enabled = !load.running,
                singleLine = true,
                keyboardOptions = numberOptions,
                keyboardActions = done,
            )
            OutlinedTextField(
                window,
                { window = it.filter(Char::isDigit).take(4) },
                Modifier.weight(1f),
                label = { Text(stringResource(Res.string.load_window)) },
                enabled = !load.running,
                singleLine = true,
                keyboardOptions = numberOptions,
                keyboardActions = done,
            )
        }
        TextButton({ focus.clearFocus() }) { Text(stringResource(Res.string.load_keyboard_done)) }
        Text(stringResource(Res.string.load_rate_note), style = MaterialTheme.typography.bodySmall)
        Button(
            {
                focus.clearFocus()
                onIntent(LabIntent.Load(plan))
            },
            enabled = canStart && (mode == LoadMode.ECHO || state.mode == LabMode.DISCOVER),
        ) {
            Text(stringResource(Res.string.load_start))
        }
        OutlinedButton(
            {
                focus.clearFocus()
                onIntent(LabIntent.Load(plan, suite = true))
            },
            enabled = canStart && (mode == LoadMode.ECHO || state.mode == LabMode.DISCOVER),
        ) {
            Text(stringResource(Res.string.load_suite))
        }
        if (state.mode == LabMode.HOST)
            OutlinedButton(
                {
                    focus.clearFocus()
                    onIntent(LabIntent.Load(plan, clients = true))
                },
                enabled = canStart,
            ) {
                Text(stringResource(Res.string.load_clients))
            }
        OutlinedButton({ onIntent(LabIntent.CancelLoad) }) {
            Text(stringResource(Res.string.load_cancel))
        }
        Text(stringResource(Res.string.load_suite_note), style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        if (!load.running) Res.string.load_idle
                        else if (load.draining) Res.string.load_draining
                        else Res.string.load_active,
                    ),
                )
                if (load.remotePending > 0)
                    Text(stringResource(Res.string.load_waiting_clients, load.remotePending))
                if (load.run.isNotEmpty())
                    Text(load.run, style = MaterialTheme.typography.labelSmall)
                Text(stringResource(Res.string.load_live, load.sent, load.delivered, load.pending))
                Text(stringResource(Res.string.load_queue, load.queuedBytes, load.progressMs))
                Text(stringResource(Res.string.load_wire, load.wireTxBytes, load.wireRxBytes))
                Text(
                    stringResource(
                        Res.string.load_received,
                        load.received,
                        load.duplicates,
                        load.corrupt,
                    ),
                )
            }
        }
        Text(
            stringResource(Res.string.load_measure_note),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(Res.string.load_stop_note), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(Res.string.replica_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(Res.string.node_id, load.nodeId),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                Res.string.replica_value,
                load.replica.epoch,
                load.replica.revision,
                load.replica.counter,
            ),
        )
        Text(stringResource(Res.string.replica_hash, load.replicaHash))
        if (load.migration.isNotEmpty())
            Text(
                stringResource(
                    Res.string.migration_status,
                    load.migration,
                    load.migrationElapsedMs,
                    load.migrationConnected,
                    load.migrationExpected,
                ),
            )
        if (state.mode == LabMode.HOST)
            connected.forEach { peer ->
                OutlinedButton(
                    { onIntent(LabIntent.Handoff(peer.id)) },
                    enabled = peer.id in load.identities,
                ) {
                    Text(stringResource(Res.string.handoff_to, "${peer.name} (${peer.id})"))
                }
            }
        if (state.mode != LabMode.HOST)
            OutlinedButton({ onIntent(LabIntent.TakeOver) }, enabled = connected.isEmpty()) {
                Text(stringResource(Res.string.takeover))
            }
        Text(stringResource(Res.string.migration_note), style = MaterialTheme.typography.bodySmall)
        if (load.results.isNotEmpty())
            Text(
                stringResource(Res.string.load_results),
                style = MaterialTheme.typography.titleLarge,
            )
        load.results.takeLast(5).reversed().forEach { ResultCard(it) }
        if (load.remoteResults.isNotEmpty())
            Text(
                stringResource(Res.string.load_remote_results),
                style = MaterialTheme.typography.titleLarge,
            )
        load.remoteResults.takeLast(5).reversed().forEach { ResultCard(it) }
    }
}

@Composable
private fun ResultCard(r: LoadResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${r.plan.mode} · ${r.reason}", style = MaterialTheme.typography.titleMedium)
            Text(r.run, style = MaterialTheme.typography.labelSmall)
            Text(
                stringResource(
                    Res.string.load_result_plan,
                    r.plan.bytes,
                    r.plan.rate,
                    r.plan.seconds,
                    r.plan.window,
                ),
            )
            Text(
                stringResource(
                    Res.string.load_result_counts,
                    r.sent,
                    r.delivered,
                    r.timedOut,
                    r.failed,
                    r.cancelled,
                ),
            )
            Text(
                stringResource(
                    Res.string.load_result_rate,
                    r.goodputBytesPerSecond,
                    r.deliveredBytes,
                    r.elapsedMs,
                ),
            )
            Text(stringResource(Res.string.load_wire, r.wireTxBytes, r.wireRxBytes))
            Text(
                stringResource(
                    Res.string.load_result_latency,
                    r.latency.p50,
                    r.latency.p95,
                    r.latency.p99,
                    r.latency.max,
                    r.latency.samples,
                ),
            )
            Text(
                stringResource(
                    Res.string.load_result_limits,
                    r.peakPending,
                    r.peakQueuedBytes,
                    r.limitedTicks,
                ),
            )
            Text(
                stringResource(
                    Res.string.load_result_anomalies,
                    r.duplicates,
                    r.outOfOrder,
                    r.corrupt,
                ),
            )
            r.peers.forEach { p ->
                Text(
                    stringResource(
                        Res.string.load_result_peer,
                        p.endpoint,
                        p.sent,
                        p.delivered,
                        p.timeout,
                        p.failed,
                        p.latency.p95,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
