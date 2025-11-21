package ws.nyaa.silicatool

import android.nfc.Tag
import android.nfc.tech.NfcF
import android.util.Log
import java.io.IOException
import kotlin.math.min

private const val BLOCK_SIZE = 16
private const val MAX_SERVICE_SCAN = 0xFFFF
private const val MAX_SYSTEM_CODES = 4
private const val MAX_SERVICE_CODES = 4
private const val READ_MAX_BLOCKS = 0xFFFF
private const val WRITE_MAX_BLOCKS = 12
private const val SERVICE_CODE_SYSTEM_BLOCK = 0xFFFF
private val DEFAULT_PMM = byteArrayOf(
    0x00,
    0x01,
    0xFF.toByte(),
    0xFF.toByte(),
    0xFF.toByte(),
    0xFF.toByte(),
    0xFF.toByte(),
    0xFF.toByte()
)
private const val LOG_TAG = "FelicaClient"

sealed interface SearchServiceResult {
    data class Service(val code: Int) : SearchServiceResult
    data class AreaSkip(val areaCode: Int, val endIndex: Int) : SearchServiceResult
    data object End : SearchServiceResult
}

class FelicaClient(private val tag: Tag) {
    private val nfcF: NfcF =
        NfcF.get(tag) ?: throw IllegalStateException("NFC-F に対応していないタグです")

    fun readCard(): CardDump {
        nfcF.connect()
        try {
            val initialIdm = tag.id
            val initialPmm = DEFAULT_PMM

            val systemCodes = requestSystemCodes(initialIdm).ifEmpty { listOf(0xFFFF) }

            val systems = mutableListOf<SystemDump>()
            var primaryIdm = initialIdm
            var primaryPmm = initialPmm

            systemCodes.forEachIndexed { idx, systemCode ->
                val polled = pollSystem(systemCode)
                val idmForSystem = polled?.first ?: initialIdm
                val pmmForSystem = polled?.second ?: initialPmm
                if (idx == 0) {
                    primaryIdm = idmForSystem
                    primaryPmm = pmmForSystem
                }

                val services = discoverServices(idmForSystem)
                val serviceDumps = services
                    .groupBy { it ushr 6 }
                    .map { (serviceNumber, codes) ->
                        val primary = codes.firstOrNull { it and 0x1 == 0x1 } ?: codes.first()
                        val blocks = readAvailableBlocks(idmForSystem, primary)
                        val blockCount = blocks.size
                        val error =
                            if (blocks.isEmpty()) "ブロック情報が取得できませんでした" else null
                        ServiceDump(
                            serviceNumber = serviceNumber,
                            primaryServiceCode = primary,
                            serviceCodes = codes.sorted(),
                            systemCode = systemCode,
                            blockCount = blockCount,
                            blocks = blocks,
                            error = error,
                        )
                    }
                    .filter {
                        val keep = it.blocks.isNotEmpty()
                        if (!keep) {
                            Log.d(
                                LOG_TAG,
                                "サービス番号 0x${it.serviceNumber.toString(16)} を除外: ブロックなし"
                            )
                        }
                        keep
                    }

                systems += SystemDump(systemCode, idmForSystem, pmmForSystem, serviceDumps)
            }

            return CardDump(idm = primaryIdm, pmm = primaryPmm, systems = systems)
        } finally {
            try {
                nfcF.close()
            } catch (_: Exception) {
            }
        }
    }

    fun writeSiliCa(image: SiliCaImage) {
        nfcF.connect()
        try {
            val idm = tag.id
            if (idm.size != 8) throw IOException("IDm 長が不正です")
            writeBlock(idm, 0x83, buildIdmBlock(image.idm, image.pmm))
            writeBlock(idm, 0x84, buildServiceBlock(image.serviceCodes))
            writeBlock(idm, 0x85, buildSystemBlock(image.systemCodes))

            image.blocks.take(WRITE_MAX_BLOCKS).forEachIndexed { index, bytes ->
                if (bytes.size != BLOCK_SIZE) {
                    throw IOException("ブロック${index}のサイズが16バイトではありません")
                }
                writeBlock(idm, index, bytes)
            }
        } finally {
            try {
                nfcF.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeBlock(idm: ByteArray, blockNumber: Int, data: ByteArray) {
        val frame = buildWriteCommand(idm, blockNumber, data)
        val response = transceive(frame)
        if (response.size < 12 || response[1].toInt() != 0x09) {
            throw IOException("書き込みレスポンスが不正です")
        }
        val sf1 = response[10].toInt() and 0xFF
        val sf2 = response[11].toInt() and 0xFF
        if (sf1 != 0 || sf2 != 0) {
            throw IOException("書き込みエラー: SF1=${sf1.toString(16)}, SF2=${sf2.toString(16)}")
        }
    }

    private fun requestSystemCodes(idm: ByteArray): List<Int> {
        val frame = buildRequestSystemCodeCommand(idm)
        val response = transceive(frame)
        if (response.size < 12 || response[1].toInt() != 0x0D) {
            return emptyList()
        }
        val count = response[10].toInt() and 0xFF
        if (count == 0) return emptyList()
        val codes = mutableListOf<Int>()
        var offset = 11
        repeat(count) {
            if (offset + 1 >= response.size) return@repeat
            val value =
                ((response[offset].toInt() and 0xFF) shl 8) or (response[offset + 1].toInt() and 0xFF)
            codes += value
            offset += 2
        }
        return codes
    }

    private fun discoverServices(idm: ByteArray): List<Int> {
        val services = mutableListOf<Int>()
        var index = 0
        while (index < MAX_SERVICE_SCAN) {
            val result = searchServiceCode(idm, index) ?: break
            when (result) {
                is SearchServiceResult.Service -> {
                    if (result.code and 0x1 == 0x1) {
                        services += result.code
                    } else {
                        Log.d(
                            LOG_TAG,
                            "サービスコード0x${result.code.toString(16)}（暗号領域）は除外しました"
                        )
                    }
                }

                is SearchServiceResult.AreaSkip -> {
                    Log.d(
                        LOG_TAG,
                        "SearchServiceCode area=0x${result.areaCode.toString(16)} end=0x${
                            result.endIndex.toString(16)
                        } (area response)"
                    )
                }

                SearchServiceResult.End -> {
                    break
                }
            }
            index += 1
        }
        return services
    }

    private fun readAvailableBlocks(idm: ByteArray, serviceCode: Int): List<BlockData> {
        val blocks = mutableListOf<BlockData>()
        for (blockNumber in 0 until READ_MAX_BLOCKS) {
            val result = readWithoutEncryption(idm, serviceCode, listOf(blockNumber))
            if (result.isEmpty()) break
            blocks += BlockData(blockNumber, result.first())
        }
        return blocks
    }

    private fun readWithoutEncryption(
        idm: ByteArray,
        serviceCode: Int,
        blockNumbers: List<Int>
    ): List<ByteArray> {
        if (blockNumbers.isEmpty()) return emptyList()
        val frame = buildReadWithoutEncryptionCommand(idm, serviceCode, blockNumbers)
        val response = transceive(frame)
        if (response.size < 12 || response[1].toInt() != 0x07) {
            return emptyList()
        }
        val sf1 = response[10].toInt() and 0xFF
        val sf2 = response[11].toInt() and 0xFF
        if (sf1 != 0 || sf2 != 0) {
            return emptyList()
        }
        val blocks = mutableListOf<ByteArray>()
        var offset = 13
        while (offset + BLOCK_SIZE <= response.size && blocks.size < blockNumbers.size) {
            blocks += response.copyOfRange(offset, offset + BLOCK_SIZE)
            offset += BLOCK_SIZE
        }
        return blocks
    }

    private fun searchServiceCode(idm: ByteArray, serviceIndex: Int): SearchServiceResult? {
        val frame = buildSearchServiceCodeCommand(idm, serviceIndex)
        val response = transceive(frame)
        if (response.size < 12 || response[1].toInt() != 0x0B) {
            return null
        }
        val payload = response.copyOfRange(10, response.size)
        return when (payload.size) {
            2 -> {
                if (payload[0] == 0xFF.toByte() && payload[1] == 0xFF.toByte()) {
                    SearchServiceResult.End
                } else {
                    val code =
                        (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
                    SearchServiceResult.Service(code)
                }
            }

            4 -> {
                val areaCode =
                    (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
                val endIndex =
                    (payload[2].toInt() and 0xFF) or ((payload[3].toInt() and 0xFF) shl 8)
                SearchServiceResult.AreaSkip(areaCode, endIndex)
            }

            else -> null
        }
    }

    private fun buildIdmBlock(idm: ByteArray, pmm: ByteArray): ByteArray {
        val result = ByteArray(BLOCK_SIZE) { 0x00 }
        val clampedPmm = ByteArray(8) { 0xFF.toByte() }
        val pmmLength = min(pmm.size, 2)
        System.arraycopy(idm, 0, result, 0, min(idm.size, 8))
        System.arraycopy(pmm, 0, clampedPmm, 0, pmmLength)
        System.arraycopy(clampedPmm, 0, result, 8, clampedPmm.size)
        return result
    }

    private fun buildServiceBlock(serviceCodes: List<Int>): ByteArray {
        val result = ByteArray(BLOCK_SIZE) { 0x00 }
        serviceCodes.take(MAX_SERVICE_CODES).forEachIndexed { idx, code ->
            val offset = idx * 2
            if (offset + 1 < result.size) {
                result[offset] = (code and 0xFF).toByte()
                result[offset + 1] = ((code shr 8) and 0xFF).toByte()
            }
        }
        return result
    }

    private fun buildSystemBlock(systemCodes: List<Int>): ByteArray {
        val result = ByteArray(BLOCK_SIZE) { 0x00 }
        systemCodes.take(MAX_SYSTEM_CODES).forEachIndexed { idx, code ->
            val offset = idx * 2
            if (offset + 1 < result.size) {
                result[offset] = ((code shr 8) and 0xFF).toByte()
                result[offset + 1] = (code and 0xFF).toByte()
            }
        }
        return result
    }

    private fun buildWriteCommand(idm: ByteArray, blockNumber: Int, payload: ByteArray): ByteArray {
        val serviceCode = SERVICE_CODE_SYSTEM_BLOCK
        val serviceCodeLe =
            byteArrayOf((serviceCode and 0xFF).toByte(), ((serviceCode shr 8) and 0xFF).toByte())
        val blockList = byteArrayOf((0x80 or 0x00).toByte(), blockNumber.toByte())

        val body = ByteArray(1 + 8 + 1 + 2 + 1 + blockList.size + payload.size)
        var offset = 0
        body[offset++] = 0x08
        System.arraycopy(idm, 0, body, offset, 8)
        offset += 8
        body[offset++] = 0x01
        System.arraycopy(serviceCodeLe, 0, body, offset, 2)
        offset += 2
        body[offset++] = 0x01
        System.arraycopy(blockList, 0, body, offset, blockList.size)
        offset += blockList.size
        System.arraycopy(payload, 0, body, offset, payload.size)

        return addLength(body)
    }

    private fun buildReadWithoutEncryptionCommand(
        idm: ByteArray,
        serviceCode: Int,
        blockNumbers: List<Int>
    ): ByteArray {
        val serviceLe =
            byteArrayOf((serviceCode and 0xFF).toByte(), ((serviceCode shr 8) and 0xFF).toByte())
        val blockList = ByteArray(blockNumbers.size * 2)
        var pos = 0
        blockNumbers.forEach { blockNumber ->
            blockList[pos++] = (0x80 or 0x00).toByte()
            blockList[pos++] = blockNumber.toByte()
        }

        val body = ByteArray(1 + 8 + 1 + 2 + 1 + blockList.size)
        var offset = 0
        body[offset++] = 0x06
        System.arraycopy(idm, 0, body, offset, 8)
        offset += 8
        body[offset++] = 0x01
        System.arraycopy(serviceLe, 0, body, offset, 2)
        offset += 2
        body[offset++] = blockNumbers.size.toByte()
        System.arraycopy(blockList, 0, body, offset, blockList.size)

        return addLength(body)
    }

    private fun buildRequestSystemCodeCommand(idm: ByteArray): ByteArray {
        val body = ByteArray(1 + 8)
        body[0] = 0x0C
        System.arraycopy(idm, 0, body, 1, 8)
        return addLength(body)
    }

    private fun buildSearchServiceCodeCommand(idm: ByteArray, serviceIndex: Int): ByteArray {
        val body = ByteArray(1 + 8 + 2)
        body[0] = 0x0A
        System.arraycopy(idm, 0, body, 1, 8)
        body[9] = (serviceIndex and 0xFF).toByte()
        body[10] = ((serviceIndex shr 8) and 0xFF).toByte()
        return addLength(body)
    }

    private fun pollSystem(systemCode: Int): Pair<ByteArray, ByteArray>? {
        val body = ByteArray(1 + 2 + 1 + 1)
        body[0] = 0x00
        body[1] = ((systemCode shr 8) and 0xFF).toByte()
        body[2] = (systemCode and 0xFF).toByte()
        body[3] = 0x01
        body[4] = 0x0F
        val frame = addLength(body)
        val response = transceive(frame)
        if (response.size < 18 || response[1].toInt() != 0x01) {
            return null
        }
        val idm = response.copyOfRange(2, 10)
        val pmm = response.copyOfRange(10, 18)
        return idm to pmm
    }

    private fun addLength(payload: ByteArray): ByteArray {
        val frame = ByteArray(payload.size + 1)
        frame[0] = (payload.size + 1).toByte()
        System.arraycopy(payload, 0, frame, 1, payload.size)
        return frame
    }

    private fun transceive(frame: ByteArray, timeoutMs: Int = 500): ByteArray {
        nfcF.timeout = timeoutMs
        Log.d(LOG_TAG, "TX (${frame.size}B): ${frame.toHexString()}")
        val response = nfcF.transceive(frame)
        Log.d(LOG_TAG, "RX (${response.size}B): ${response.toHexString()}")
        return response
    }
}
