package ws.nyaa.silicatool

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MainScreen(
    state: MainUiState,
    onStartRead: () -> Unit,
    onToggleSystem: (Int, Boolean) -> Unit,
    onToggleService: (Int, Boolean) -> Unit,
    onSelectWriteService: (Int) -> Unit,
    onRequestWrite: () -> Unit,
    onCancelTrim: () -> Unit,
    onConfirmTrim: () -> Unit,
    onShowServiceDetail: (ServiceDump) -> Unit,
    onDismissDetail: () -> Unit,
    onNextStep: (WizardStep) -> Unit,
    onBackStep: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val isAwaitingRead = state.wizardStep == WizardStep.AwaitRead
    Scaffold(
        topBar = { TopAppBar(title = { Text("FeliCa to SiliCa") }) }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                status = state.status,
                error = state.error,
                awaitingWrite = state.awaitingWrite
            )

            if (state.wizardStep == WizardStep.AwaitRead) {
                Button(
                    onClick = onStartRead,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isBusy && state.readerStage == ReaderStage.Idle
                ) {
                    Text("FeliCa を読み取る")
                }
            }

            val cardDump = state.cardDump
            if (cardDump != null && !isAwaitingRead) {
                CardInfo(cardDump)
                when (state.wizardStep) {
                    WizardStep.AwaitRead -> {
                        Text("FeliCa を読み取ってください")
                    }

                    WizardStep.SystemSelect -> {
                        SystemSelection(cardDump, state.selection, onToggleSystem)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onBackStep) {
                                Text("戻る")
                            }
                            TextButton(
                                onClick = { onNextStep(WizardStep.ServiceSelect) },
                                enabled = true
                            ) {
                                Text("次へ")
                            }
                        }
                    }

                    WizardStep.ServiceSelect -> {
                        ServiceSelection(
                            cardDump,
                            state.selection,
                            onToggleService,
                            onSelectWriteService,
                            onShowServiceDetail
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            val canProceed =
                                state.selection.selectedServices.isNotEmpty() && state.selection.writeService != null
                            TextButton(onClick = onBackStep) {
                                Text("戻る")
                            }
                            TextButton(
                                onClick = { onNextStep(WizardStep.ReadyToWrite) },
                                enabled = canProceed
                            ) {
                                Text("書き込み準備へ")
                            }
                        }
                    }

                    WizardStep.ReadyToWrite -> {
                        SelectionSummary(cardDump, state.selection)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onBackStep) { Text("戻る") }
                        }
                        Button(
                            onClick = onRequestWrite,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isBusy && !state.awaitingWrite
                        ) {
                            Text("SiliCa に書き込む")
                        }
                    }
                }
            }
        }
    }

    state.trimPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = onCancelTrim,
            confirmButton = {
                TextButton(onClick = onConfirmTrim) { Text("トリミングして続行") }
            },
            dismissButton = {
                TextButton(onClick = onCancelTrim) { Text("中断") }
            },
            title = { Text("ブロック数が多すぎます") },
            text = {
                Text(
                    "サービス ${prompt.serviceCode.toHexShort()} は ${prompt.blockCount} ブロックあります。先頭12ブロックにトリミングしてよろしいですか？"
                )
            }
        )
    }

    state.serviceDetail?.let {
        ServiceDetailDialog(it, onDismissDetail)
    }

    BackHandler(enabled = state.wizardStep != WizardStep.AwaitRead) {
        onBackStep()
    }
}

@Composable
private fun StatusCard(status: String, error: String?, awaitingWrite: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val background = if (error != null) colorScheme.errorContainer else colorScheme.surfaceVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = background),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(status, fontWeight = FontWeight.Bold)
            if (awaitingWrite) {
                Text("SiliCa をかざすと書き込みます。", fontSize = 14.sp)
            }
            if (error != null) {
                Text(error, color = Color.Red, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun CardInfo(cardDump: CardDump) {
    val background = MaterialTheme.colorScheme.surfaceVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = background),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("読み取ったカード", fontWeight = FontWeight.Bold)
            Text("IDm: ${cardDump.idm.toHexString()}")
            Text("PMm: ${cardDump.pmm.toHexString()}")
            Text("システムコード: ${cardDump.systems.joinToString { it.systemCode.toHexShort() }}")
        }
    }
}

@Composable
private fun SystemSelection(
    cardDump: CardDump,
    selection: SelectionState,
    onToggleSystem: (Int, Boolean) -> Unit,
) {
    val background = MaterialTheme.colorScheme.surfaceVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = background),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "システム選択（最大${WriteConstraints.maxSelectableSystems}）",
                fontWeight = FontWeight.Bold
            )
            cardDump.systems.forEach { system ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = selection.selectedSystems.contains(system.systemCode),
                        onCheckedChange = { checked -> onToggleSystem(system.systemCode, checked) }
                    )
                    Text("システム ${system.systemCode.toHexShort()}")
                }
            }
        }
    }
}

@Composable
private fun ServiceSelection(
    cardDump: CardDump,
    selection: SelectionState,
    onToggleService: (Int, Boolean) -> Unit,
    onSelectWriteService: (Int) -> Unit,
    onShowServiceDetail: (ServiceDump) -> Unit,
) {
    val background = MaterialTheme.colorScheme.surfaceVariant
    val systemsToShow = cardDump.systems
    Card(
        colors = CardDefaults.cardColors(containerColor = background),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "サービス選択（最大${WriteConstraints.maxSelectableServices}）",
                fontWeight = FontWeight.Bold
            )
            Text("チェックボックス: SiliCaに登録するサービスコード（最大${WriteConstraints.maxSelectableServices}）。")
            Text("ラジオボタン: ブロックを書き込むサービスコード（1つ）。")
            var hasService = false
            systemsToShow.forEach { system ->
                Text("システム ${system.systemCode.toHexShort()}", fontWeight = FontWeight.Bold)
                system.services.forEach { service ->
                    hasService = true
                    val selected = selection.selectedServices.contains(service.primaryServiceCode)
                    val isWriteTarget = selection.writeService == service.primaryServiceCode
                    val serviceCardColor =
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = serviceCardColor,
                        shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        onToggleService(
                                            service.primaryServiceCode,
                                            checked
                                        )
                                    }
                                )
                                Text(
                                    "サービス番号 ${service.serviceNumber.toHexShort()}",
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                RadioButton(
                                    selected = isWriteTarget,
                                    onClick = { onSelectWriteService(service.primaryServiceCode) }
                                )
                            }
                            Text("サービスコード: ${service.serviceCodes.joinToString { it.toHexShort() }}")
                            Text("ブロック数: ${service.blockCount}")
                            if (service.error != null) {
                                Text(service.error, color = Color.Red, fontSize = 14.sp)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { onShowServiceDetail(service) }) {
                                    Text("ブロック表示")
                                }
                            }
                        }
                    }
                }
            }
            if (!hasService) {
                Text("サービス情報が取得できませんでした。")
            }
        }
    }
}

@Composable
private fun SelectionSummary(cardDump: CardDump, selection: SelectionState) {
    val systems = if (selection.selectedSystems.isNotEmpty()) {
        cardDump.systems.filter { selection.selectedSystems.contains(it.systemCode) }
    } else {
        cardDump.systems
    }
    val services = systems.flatMap { it.services }.let { list ->
        if (selection.selectedServices.isNotEmpty()) {
            list.filter { selection.selectedServices.contains(it.primaryServiceCode) }
        } else {
            list
        }
    }
    val serviceCodes = services.flatMap { it.serviceCodes }.distinct()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("書き込み内容確認", fontWeight = FontWeight.Bold)
            Text("システム: ${systems.joinToString { it.systemCode.toHexShort() }}")
            Text("サービスコード: ${serviceCodes.joinToString { it.toHexShort() }}")
            val target = selection.writeService?.toHexShort() ?: "未選択"
            Text("ブロックを書き込むサービスコード: $target")
        }
    }
}

@Composable
private fun ServiceDetailDialog(service: ServiceDump, onDismiss: () -> Unit) {
    val shiftJis = Charset.forName("Shift_JIS")
    val decoder = shiftJis.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .apply { replaceWith(".") }
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
        title = { Text("サービス番号 ${service.serviceNumber.toHexShort()} のブロック") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(scrollState)
            ) {
                service.blocks.forEach { block ->
                    val hex = block.data.toHexString()
                    val decoded = runCatching {
                        decoder.reset()
                        val decoded = decoder.decode(ByteBuffer.wrap(block.data)).toString()
                        buildString {
                            decoded.forEach { ch ->
                                if (ch == '\u0000') append('.') else append(ch)
                            }
                        }
                    }.getOrElse { "（Shift_JIS デコード不可）" }
                    Text("ブロック${block.blockNumber}: $hex")
                    Text("文字列: $decoded", fontSize = 14.sp)
                }
                if (service.blocks.isEmpty()) {
                    Text("ブロックがありません")
                }
            }
        }
    )
}
