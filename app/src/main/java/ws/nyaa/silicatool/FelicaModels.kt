package ws.nyaa.silicatool

import java.util.Locale

data class BlockData(val blockNumber: Int, val data: ByteArray)

data class ServiceDump(
    val serviceNumber: Int,
    val primaryServiceCode: Int,
    val serviceCodes: List<Int>,
    val systemCode: Int,
    val blockCount: Int,
    val blocks: List<BlockData>,
    val error: String? = null,
)

data class SystemDump(
    val systemCode: Int,
    val idm: ByteArray,
    val pmm: ByteArray,
    val services: List<ServiceDump>,
)

data class CardDump(
    val idm: ByteArray,
    val pmm: ByteArray,
    val systems: List<SystemDump>,
)

data class SelectionState(
    val selectedSystems: Set<Int> = emptySet(),
    val selectedServices: Set<Int> = emptySet(),
    val writeService: Int? = null,
)

data class SiliCaImage(
    val idm: ByteArray,
    val pmm: ByteArray,
    val systemCodes: List<Int>,
    val serviceCodes: List<Int>,
    val blocks: List<ByteArray>,
)

fun ByteArray.toHexString(): String = joinToString("") { b ->
    String.format(Locale.US, "%02X", b)
}

fun Int.toHexShort(): String = String.format(Locale.US, "%04X", this and 0xFFFF)
