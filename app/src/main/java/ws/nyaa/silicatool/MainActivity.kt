package ws.nyaa.silicatool

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ws.nyaa.silicatool.ui.theme.SiliCaToolTheme
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

private enum class ReaderStage { Idle, AwaitingSource, AwaitingWrite }
private enum class WizardStep { AwaitRead, SystemSelect, ServiceSelect, ReadyToWrite }

private data class TrimPrompt(val serviceCode: Int, val blockCount: Int)

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private var nfcAdapter: NfcAdapter? = null

    private val statusText = mutableStateOf("カードを読み取ってください")
    private val errorText = mutableStateOf<String?>(null)
    private val cardDumpState = mutableStateOf<CardDump?>(null)
    private val selectionState = mutableStateOf(SelectionState())
    private val isBusy = mutableStateOf(false)
    private val readerStage = mutableStateOf(ReaderStage.Idle)
    private val awaitingWrite = mutableStateOf(false)
    private val trimPrompt = mutableStateOf<TrimPrompt?>(null)
    private val serviceDetail = mutableStateOf<ServiceDump?>(null)
    private val wizardStep = mutableStateOf(WizardStep.AwaitRead)
    private var pendingImage: SiliCaImage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            statusText.value = "この端末はNFCに対応していません"
        }

        setContent {
            SiliCaToolTheme {
                MainScreen(
                    status = statusText.value,
                    error = errorText.value,
                    cardDump = cardDumpState.value,
                    selection = selectionState.value,
                    isBusy = isBusy.value,
                    awaitingWrite = awaitingWrite.value,
                    trimPrompt = trimPrompt.value,
                    serviceDetail = serviceDetail.value,
                    wizardStep = wizardStep.value,
                    onStartRead = { startReadMode() },
                    onToggleSystem = { systemCode, checked -> toggleSystem(systemCode, checked) },
                    onToggleService = { serviceCode, checked ->
                        toggleService(
                            serviceCode,
                            checked
                        )
                    },
                    onSelectWriteService = { serviceCode -> selectWriteService(serviceCode) },
                    onRequestWrite = { requestWriteFlow(trimAllowed = false) },
                    onCancelTrim = { trimPrompt.value = null },
                    onConfirmTrim = { requestWriteFlow(trimAllowed = true) },
                    onShowServiceDetail = { serviceDetail.value = it },
                    onDismissDetail = { serviceDetail.value = null },
                    onNextStep = { wizardStep.value = it },
                    onBackStep = { stepBack() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
    }

    override fun onTagDiscovered(tag: Tag) {
        when (readerStage.value) {
            ReaderStage.AwaitingSource -> readFromTag(tag)
            ReaderStage.AwaitingWrite -> writeToSiliCa(tag)
            ReaderStage.Idle -> Unit
        }
    }

    private fun startReadMode() {
        if (isBusy.value) return
        statusText.value = "FeliCa をかざしてください"
        errorText.value = null
        readerStage.value = ReaderStage.AwaitingSource
    }

    private fun readFromTag(tag: Tag) {
        if (isBusy.value) return
        isBusy.value = true
        statusText.value = "読み取り中..."
        errorText.value = null
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { FelicaClient(tag).readCard() }
            withContext(Dispatchers.Main) {
                isBusy.value = false
                readerStage.value = ReaderStage.Idle
                if (result.isSuccess) {
                    val dump = result.getOrThrow()
                    cardDumpState.value = dump
                    selectionState.value = SelectionState(
                        selectedSystems = dump.systems.take(4).map { it.systemCode }.toSet(),
                        selectedServices = dump.systems.firstOrNull()?.services?.take(1)
                            ?.map { it.primaryServiceCode }?.toSet() ?: emptySet(),
                        writeService = dump.systems.firstOrNull()?.services?.firstOrNull()?.primaryServiceCode
                    )
                    statusText.value = "読み取り完了。システムとサービスを選んでください。"
                    wizardStep.value = WizardStep.SystemSelect
                } else {
                    errorText.value = "読み取りに失敗しました: ${result.exceptionOrNull()?.message}"
                    statusText.value = "カードを読み取ってください"
                }
            }
        }
    }

    private fun requestWriteFlow(trimAllowed: Boolean) {
        pendingImage = null
        awaitingWrite.value = false
        val card = cardDumpState.value ?: run {
            statusText.value = "先にFeliCaを読み取ってください"
            return
        }
        val selection = selectionState.value
        val systems = if (selection.selectedSystems.isNotEmpty()) {
            selection.selectedSystems
        } else {
            card.systems.take(4).map { it.systemCode }.toSet()
        }
        if (systems.size > 4) {
            statusText.value = "システムは最大4つまで選択できます"
            return
        }

        val systemsForWrite =
            card.systems.filter { systems.contains(it.systemCode) }.ifEmpty { card.systems }
        val selectedServiceCodes =
            if (selection.selectedServices.isNotEmpty()) selection.selectedServices else emptySet()
        val allServices = systemsForWrite.flatMap { it.services }
        val servicesForWrite = if (selectedServiceCodes.isNotEmpty()) {
            allServices.filter { selectedServiceCodes.contains(it.primaryServiceCode) }
        } else {
            allServices
        }
        if (servicesForWrite.isEmpty()) {
            statusText.value = "サービス情報が取得できませんでした"
            return
        }

        val serviceCodesFromSystems = mutableListOf<Int>().apply {
            servicesForWrite.forEach { svc ->
                svc.serviceCodes.forEach { code ->
                    if (!contains(code)) add(code)
                }
            }
        }
        if (serviceCodesFromSystems.size > 4) {
            statusText.value = "選択されたシステムに含まれるサービスコードが多すぎます（最大4）"
            return
        }

        val targetServiceCode = selection.writeService
            ?.takeIf { svc -> servicesForWrite.any { it.primaryServiceCode == svc } }
            ?: servicesForWrite.firstOrNull()?.primaryServiceCode
            ?: run {
                statusText.value = "書き込むサービスを1つ選択してください"
                return
            }

        val serviceCodeSetForWrite = serviceCodesFromSystems.toMutableList()
        if (!serviceCodeSetForWrite.contains(targetServiceCode)) {
            serviceCodeSetForWrite.add(0, targetServiceCode)
        }

        val serviceDump =
            servicesForWrite.firstOrNull { it.primaryServiceCode == targetServiceCode }
                ?: run {
                    statusText.value = "選択したサービスのデータがありません"
                    return
                }

        if (serviceDump.blocks.isEmpty()) {
            statusText.value = "ブロックデータが取得できていません"
            return
        }

        if (serviceDump.blockCount > 12 && !trimAllowed) {
            trimPrompt.value = TrimPrompt(serviceDump.primaryServiceCode, serviceDump.blockCount)
            return
        }

        val trimmedBlocks = serviceDump.blocks.take(12).map { it.data }
        val paddedBlocks = if (trimmedBlocks.size < 12) {
            trimmedBlocks + List(12 - trimmedBlocks.size) { ByteArray(16) }
        } else {
            trimmedBlocks
        }
        val serviceCodesForImage = serviceCodeSetForWrite.take(4)
        val systemCodesForImage = systems.take(4).toList()
        pendingImage = SiliCaImage(
            idm = card.idm,
            pmm = card.pmm,
            systemCodes = systemCodesForImage,
            serviceCodes = serviceCodesForImage,
            blocks = paddedBlocks,
        )
        trimPrompt.value = null
        awaitingWrite.value = true
        statusText.value = "SiliCa をかざしてください"
        readerStage.value = ReaderStage.AwaitingWrite
        wizardStep.value = WizardStep.ReadyToWrite
    }

    private fun stepBack() {
        wizardStep.value = when (wizardStep.value) {
            WizardStep.ReadyToWrite -> WizardStep.ServiceSelect
            WizardStep.ServiceSelect -> WizardStep.SystemSelect
            WizardStep.SystemSelect, WizardStep.AwaitRead -> WizardStep.AwaitRead
        }
    }

    private fun writeToSiliCa(tag: Tag) {
        val image = pendingImage ?: return
        if (isBusy.value) return
        isBusy.value = true
        statusText.value = "SiliCa に書き込み中..."
        errorText.value = null
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { FelicaClient(tag).writeSiliCa(image) }
            withContext(Dispatchers.Main) {
                isBusy.value = false
                awaitingWrite.value = false
                readerStage.value = ReaderStage.Idle
                if (result.isSuccess) {
                    statusText.value = "書き込みが完了しました"
                    pendingImage = null
                } else {
                    errorText.value = "書き込みに失敗しました: ${result.exceptionOrNull()?.message}"
                    statusText.value = "もう一度お試しください"
                }
            }
        }
    }

    private fun toggleSystem(systemCode: Int, checked: Boolean) {
        val current = selectionState.value
        val updated = current.selectedSystems.toMutableSet()
        if (checked) {
            if (!updated.contains(systemCode) && updated.size >= 4) {
                statusText.value = "システムは4つまで選択できます"
                return
            }
            updated += systemCode
        } else {
            updated -= systemCode
        }
        selectionState.value = current.copy(selectedSystems = updated)
    }

    private fun toggleService(serviceCode: Int, checked: Boolean) {
        val current = selectionState.value
        val updated = current.selectedServices.toMutableSet()
        var writeService = current.writeService
        if (checked) {
            if (!updated.contains(serviceCode) && updated.size >= 4) {
                statusText.value = "サービスは4つまで選択できます"
                return
            }
            updated += serviceCode
        } else {
            updated -= serviceCode
            if (writeService == serviceCode) {
                writeService = null
            }
        }
        selectionState.value = current.copy(selectedServices = updated, writeService = writeService)
    }

    private fun selectWriteService(serviceCode: Int) {
        val current = selectionState.value
        val updated = current.selectedServices.toMutableSet()
        if (!updated.contains(serviceCode) && updated.size >= 4) {
            statusText.value = "サービスは4つまで選択できます"
            return
        }
        updated += serviceCode
        selectionState.value = current.copy(selectedServices = updated, writeService = serviceCode)
    }

    private fun enableReaderMode() {
        val flags = NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfcAdapter?.enableReaderMode(this, this, flags, null)
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainScreen(
    status: String,
    error: String?,
    cardDump: CardDump?,
    selection: SelectionState,
    isBusy: Boolean,
    awaitingWrite: Boolean,
    trimPrompt: TrimPrompt?,
    serviceDetail: ServiceDump?,
    wizardStep: WizardStep,
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
            StatusCard(status = status, error = error, awaitingWrite = awaitingWrite)

            Button(
                onClick = onStartRead,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy
            ) {
                Text("FeliCa を読み取る")
            }

            if (cardDump != null) {
                WizardIndicator(wizardStep)
                CardInfo(cardDump)
                when (wizardStep) {
                    WizardStep.AwaitRead -> {
                        Text("FeliCa を読み取ってください")
                    }

                    WizardStep.SystemSelect -> {
                        SystemSelection(cardDump, selection, onToggleSystem)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { onBackStep() }) {
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
                            selection,
                            onToggleService,
                            onSelectWriteService,
                            onShowServiceDetail
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            val canProceed =
                                selection.selectedServices.isNotEmpty() && selection.writeService != null
                            TextButton(onClick = { onBackStep() }) {
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
                        SelectionSummary(cardDump, selection)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { onBackStep() }) { Text("戻る") }
                        }
                        Button(
                            onClick = onRequestWrite,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isBusy
                        ) {
                            Text("SiliCa に書き込む")
                        }
                    }
                }
            }
        }
    }

    if (trimPrompt != null) {
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
                    "サービス ${trimPrompt.serviceCode.toHexShort()} は ${trimPrompt.blockCount} ブロックあります。先頭12ブロックにトリミングしてよろしいですか？"
                )
            }
        )
    }

    if (serviceDetail != null) {
        ServiceDetailDialog(serviceDetail, onDismissDetail)
    }

    BackHandler(enabled = wizardStep != WizardStep.AwaitRead) {
        onBackStep()
    }
}

@Composable
private fun WizardIndicator(step: WizardStep) {
    val steps = listOf(
        WizardStep.SystemSelect to "1. システム選択",
        WizardStep.ServiceSelect to "2. サービス選択",
        WizardStep.ReadyToWrite to "3. 書き込み準備"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        steps.forEach { (s, label) ->
            val active = step.ordinal >= s.ordinal
            val color =
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                    alpha = 0.4f
                )
            Text(
                label,
                color = color,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
            )
        }
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
            Text("システム選択（最大4）", fontWeight = FontWeight.Bold)
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
            Text("サービス選択（最大4）", fontWeight = FontWeight.Bold)
            Text("チェックボックス: SiliCaに登録するサービスコード（最大4）。")
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
