package org.bigmeco.nearbylab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bigmeco.nearbylab.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun NearbyLabApp() {
    val vm = viewModel { createLabViewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    val defaultName = stringResource(Res.string.default_name)
    val defaultMessage = stringResource(Res.string.default_message)
    val defaultJson = stringResource(Res.string.default_json)
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var message by rememberSaveable { mutableStateOf(defaultMessage) }
    var json by rememberSaveable { mutableStateOf(defaultJson) }
    var showLoad by rememberSaveable { mutableStateOf(false) }
    val panelState = rememberSaveableStateHolder()
    val connected = state.peers.count { it.status == PeerStatus.CONNECTED }
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Color(0xFF8AD8C4),
                surface = Color(0xFF121D26),
                background = Color(0xFF0A121A),
            ),
    ) {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column {
                        Text(
                            stringResource(Res.string.title),
                            style = MaterialTheme.typography.headlineLarge,
                        )
                        Text(
                            stringResource(Res.string.subtitle),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ showLoad = false }) {
                            Text(stringResource(Res.string.connection_tab))
                        }
                        TextButton({ showLoad = true }) {
                            Text(stringResource(Res.string.load_tab))
                        }
                    }
                }
                if (showLoad) {
                    item { Text(stringResource(Res.string.peers, connected)) }
                    item {
                        panelState.SaveableStateProvider("load") { LoadPanel(state, vm::onIntent) }
                    }
                }
                if (!showLoad) {
                    item {
                        Text(
                            stringResource(
                                when (state.mode) {
                                    LabMode.IDLE -> Res.string.idle
                                    LabMode.HOST -> Res.string.hosting
                                    LabMode.DISCOVER -> Res.string.searching
                                },
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    item {
                        OutlinedTextField(
                            name,
                            { name = it },
                            Modifier.fillMaxWidth(),
                            label = { Text(stringResource(Res.string.device_name)) },
                            singleLine = true,
                            enabled = state.mode == LabMode.IDLE,
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                { vm.onIntent(LabIntent.Start(true, name)) },
                                enabled = state.mode == LabMode.IDLE && name.isNotBlank(),
                            ) {
                                Text(stringResource(Res.string.host))
                            }
                            OutlinedButton(
                                { vm.onIntent(LabIntent.Start(false, name)) },
                                enabled = state.mode == LabMode.IDLE && name.isNotBlank(),
                            ) {
                                Text(stringResource(Res.string.discover))
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                { vm.onIntent(LabIntent.Stop) },
                                enabled = state.mode != LabMode.IDLE,
                            ) {
                                Text(stringResource(Res.string.stop))
                            }
                            TextButton({ vm.onIntent(LabIntent.Share) }) {
                                Text(stringResource(Res.string.share))
                            }
                        }
                    }
                    item {
                        Text(
                            stringResource(Res.string.peers, connected),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    if (state.peers.isEmpty())
                        item {
                            Text(
                                stringResource(Res.string.empty),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    items(state.peers, key = { it.id }) { peer ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(peer.name, style = MaterialTheme.typography.titleMedium)
                                Text(peer.id, style = MaterialTheme.typography.labelSmall)
                                if (peer.status == PeerStatus.VERIFY) {
                                    Text(
                                        stringResource(Res.string.verify, peer.code),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button({ vm.onIntent(LabIntent.Verify(peer.id, true)) }) {
                                            Text(stringResource(Res.string.accept))
                                        }
                                        TextButton(
                                            {
                                                vm.onIntent(LabIntent.Verify(peer.id, false))
                                            },
                                        ) {
                                            Text(stringResource(Res.string.reject))
                                        }
                                    }
                                } else
                                    Text(
                                        stringResource(
                                            when (peer.status) {
                                                PeerStatus.FOUND -> Res.string.found
                                                PeerStatus.CONNECTING -> Res.string.connecting
                                                PeerStatus.CONNECTED -> Res.string.connected
                                                PeerStatus.REJECTED -> Res.string.rejected
                                                else -> Res.string.disconnected
                                            },
                                        ),
                                    )
                                peer.rttMs?.let { Text(stringResource(Res.string.rtt, it)) }
                                if (peer.status == PeerStatus.FOUND)
                                    Button({ vm.onIntent(LabIntent.Connect(peer.id)) }) {
                                        Text(stringResource(Res.string.connect))
                                    }
                                if (
                                    peer.status in
                                        listOf(PeerStatus.CONNECTED, PeerStatus.CONNECTING)
                                )
                                    TextButton({ vm.onIntent(LabIntent.Disconnect(peer.id)) }) {
                                        Text(stringResource(Res.string.disconnect))
                                    }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            message,
                            { message = it },
                            Modifier.fillMaxWidth(),
                            label = { Text(stringResource(Res.string.message)) },
                        )
                    }
                    item {
                        Button(
                            { vm.onIntent(LabIntent.Send(message, false)) },
                            enabled = connected > 0,
                        ) {
                            Text(stringResource(Res.string.send_text))
                        }
                    }
                    item {
                        OutlinedTextField(
                            json,
                            { json = it },
                            Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                { vm.onIntent(LabIntent.Send(json, true)) },
                                enabled = connected > 0,
                            ) {
                                Text(stringResource(Res.string.send_json))
                            }
                            OutlinedButton(
                                { vm.onIntent(LabIntent.Ping) },
                                enabled = connected > 0,
                            ) {
                                Text(stringResource(Res.string.ping))
                            }
                        }
                    }
                }
                item {
                    Text(
                        stringResource(Res.string.physical_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    Text(
                        stringResource(Res.string.log),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                items(state.logs.takeLast(40).reversed()) { line ->
                    SelectionContainer { Text(line, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
