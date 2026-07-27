package com.ireddragonicy.konabessnext.core.scanner

import com.ireddragonicy.konabessnext.model.ChipDefinition
import com.ireddragonicy.konabessnext.model.LevelPresets
import com.ireddragonicy.konabessnext.model.dts.DtsNode
import com.ireddragonicy.konabessnext.utils.DtsTreeHelper
import com.ireddragonicy.konabessnext.domain.DtboDomainUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Describes what kind of voltage/level mechanism was detected in the DTS.
 */
enum class VoltageType {
    /** Separate OPP table with opp-hz + opp-microvolt (e.g. SD860) */
    OPP_TABLE,
    /** Inline qcom,level property inside each gpu-pwrlevel node (e.g. Tuna/SM8750) */
    INLINE_LEVEL,
    /** No voltage mechanism detected (e.g. SD660 uses generic-bw-opp-table but inline frequencies) */
    NONE
}

data class DtsScanResult(
    val isValid: Boolean,
    val dtbIndex: Int,
    val recommendedStrategy: String, // "MULTI_BIN" or "SINGLE_BIN"
    val voltageTablePattern: String?,
    val maxLevels: Int,
    val levelCount: Int = 0,
    val confidence: String = "Low", // Low, Medium, High
    val detectedModel: String? = null,
    val voltageType: VoltageType = VoltageType.NONE,
    val binCount: Int = 0,
    val detectedProperties: List<String> = emptyList(),
    val gpuNodeName: String? = null, 
    val gpuModel: String? = null, 
    val chipId: String? = null
)

object DtsScanner {

    suspend fun scan(file: File, index: Int): DtsScanResult = withContext(Dispatchers.Default) {
        if (!file.exists()) return@withContext DtsScanResult(false, index, "UNKNOWN", null, 0)

        val content = try {
            file.readText()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext DtsScanResult(false, index, "UNKNOWN", null, 0)
        }
        
        scanContent(content, index)
    }

    /**
     * Scan DTS content string directly. Dynamically figures out everything.
     */
    suspend fun scanContent(content: String, index: Int): DtsScanResult = withContext(Dispatchers.Default) {
        if (content.isBlank()) return@withContext DtsScanResult(false, index, "UNKNOWN", null, 0)

        val rootNode = try {
            DtsTreeHelper.parse(content)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext DtsScanResult(false, index, "UNKNOWN", null, 0)
        }

        // 1. Find Model String
        var modelProp = rootNode.getProperty("model")
        if (modelProp == null && rootNode.children.isNotEmpty()) {
            modelProp = rootNode.children.firstOrNull()?.getProperty("model")
        }
        val detectedModel = modelProp?.originalValue?.replace("\"", "")?.replace(";", "")?.trim()

        // 2. Find GPU node (kgsl-3d0 / gpu)
        var gpuNodeName: String? = null
        var gpuModel: String? = null
        var chipId: String? = null
        
        fun findGpuNode(node: DtsNode) {
            if (gpuNodeName != null) return
            val compat = node.getProperty("compatible")?.originalValue ?: ""
            if (node.name.contains("kgsl-3d0") || compat.contains("qcom,kgsl-3d0") || node.name.contains("gpu@") || compat.contains("adreno")) {
                gpuNodeName = node.name
                gpuModel = node.getProperty("qcom,gpu-model")?.originalValue
                    ?.replace("\"", "")?.replace(";", "")?.trim()
                chipId = node.getProperty("qcom,chipid")?.originalValue
                    ?.replace(";", "")?.trim()
                    ?.let { raw ->
                        val match = Regex("<([^>]+)>").find(raw)
                        match?.groupValues?.get(1)?.trim() ?: raw
                    }
            }
            node.children.forEach { findGpuNode(it) }
        }
        findGpuNode(rootNode)

        // 3. Strategy & Bin Detection
        val binNodes = mutableListOf<DtsNode>()
        findPwrLevels(rootNode, binNodes)

        val strategy = when {
            binNodes.size > 1 -> "MULTI_BIN"
            binNodes.size == 1 -> "SINGLE_BIN"
            else -> "UNKNOWN"
        }
        val binCount = binNodes.size

        // 4. Level Counting & Property Detection
        var maxLevelValue = 0
        var maxLevelsCount = 0
        val detectedProperties = mutableSetOf<String>()

        binNodes.forEach { binNode ->
            val levelNodes = binNode.children.filter { it.name.startsWith("qcom,gpu-pwrlevel@") }
            if (levelNodes.size > maxLevelsCount) {
                maxLevelsCount = levelNodes.size
            }
            levelNodes.forEach { node ->
                node.properties.forEach { prop ->
                    detectedProperties.add(prop.name)
                    if (prop.name == "qcom,level" || prop.name == "qcom,cx-level") {
                        val value = DtboDomainUtils.extractSingleLong(prop.originalValue).toInt()
                        if (value > maxLevelValue) maxLevelValue = value
                    }
                }
            }
        }
        
        // The highest stock qcom,level is the current operating point, not
        // the chipset's editor ceiling (Pineapple may use 388 but supports 480).
        val familyLevelCount = LevelPresets.inferSupportedLevelCount(detectedModel)
        val levelCount = when {
            familyLevelCount != null -> maxOf(familyLevelCount, maxLevelValue)
            maxLevelValue > 0 -> maxLevelValue
            else -> 480
        }
        val maxTableLevels = if (maxLevelsCount > 0) maxLevelsCount else 15

        // 5. Voltage Table Pattern Detection (OPP table style)
        val voltagePatterns = mutableSetOf<String>()
        fun findOppTables(node: DtsNode) {
            val compat = node.getProperty("compatible")?.originalValue ?: ""
            if (node.name.contains("opp-table") || compat.contains("operating-points") || compat.contains("opp-table")) {
                voltagePatterns.add(node.name)
            }
            node.children.forEach { findOppTables(it) }
        }
        findOppTables(rootNode)

        val bestVoltPattern = voltagePatterns
            .filter { it.contains("gpu") || it.contains("gfx") }
            .maxByOrNull { it.length }
            ?: voltagePatterns.firstOrNull()

        // 6. Determine voltage type
        val hasInlineLevel = detectedProperties.any { 
            it == "qcom,level" || it == "qcom,corner" || it == "qcom,bw-level" 
        }
        
        val voltageType = when {
            bestVoltPattern != null -> VoltageType.OPP_TABLE
            hasInlineLevel -> VoltageType.INLINE_LEVEL
            else -> VoltageType.NONE
        }

        // 7. Confidence calculation
        var confidenceScore = 0
        if (strategy != "UNKNOWN") confidenceScore += 2
        if (voltageType != VoltageType.NONE) confidenceScore += 1
        if (maxLevelsCount > 0) confidenceScore += 1
        if (gpuNodeName != null) confidenceScore += 1

        val confidence = when {
            confidenceScore >= 4 -> "High"
            confidenceScore >= 2 -> "Medium"
            else -> "Low"
        }

        val isValid = strategy != "UNKNOWN" && maxLevelsCount > 0

        DtsScanResult(
            isValid = isValid,
            dtbIndex = index,
            recommendedStrategy = if (strategy == "UNKNOWN") "MULTI_BIN" else strategy,
            voltageTablePattern = bestVoltPattern,
            maxLevels = maxTableLevels,
            levelCount = levelCount,
            confidence = confidence,
            detectedModel = detectedModel,
            voltageType = voltageType,
            binCount = binCount,
            detectedProperties = detectedProperties.toList().sorted(),
            gpuNodeName = gpuNodeName,
            gpuModel = gpuModel,
            chipId = chipId
        )
    }

    private fun findPwrLevels(node: DtsNode, result: MutableList<DtsNode>) {
        val compatible = node.getProperty("compatible")?.originalValue
        val pwrLevelsPattern = Regex("""qcom,gpu-pwrlevels(-\d+)?$""")
        val isNameMatch = pwrLevelsPattern.matches(node.name)
        val isCompatibleBin = compatible?.contains("qcom,gpu-pwrlevels") == true 
            && compatible.contains("bins") == false

        if (isNameMatch || isCompatibleBin) {
            result.add(node)
        }
        node.children.forEach { findPwrLevels(it, result) }
    }

    fun toChipDefinition(result: DtsScanResult): ChipDefinition {
        val binDescriptions = if (result.binCount > 1) {
            (0 until result.binCount).associate { i -> i to "Speed Bin $i" }
        } else null

        // Crucial distinction: if it's NOT an OPP table, ignore the separate voltage table screen
        val ignoreVoltTable = result.voltageType != VoltageType.OPP_TABLE

        return ChipDefinition(
            id = "custom_detected_${result.dtbIndex}_${System.currentTimeMillis()}",
            name = buildSmartName(result),
            maxTableLevels = result.maxLevels,
            ignoreVoltTable = ignoreVoltTable,
            minLevelOffset = 1,
            voltTablePattern = result.voltageTablePattern,
            strategyType = result.recommendedStrategy,
            levelCount = result.levelCount,
            levelPreset = LevelPresets.inferPreset(result.detectedModel, result.levelCount),
            binDescriptions = binDescriptions,
            models = listOf(result.detectedModel ?: "Custom")
        )
    }

    private fun buildSmartName(result: DtsScanResult): String {
        val parts = mutableListOf<String>()
        
        if (!result.gpuModel.isNullOrBlank()) {
            parts.add(
                when {
                    result.gpuModel.equals("Adreno33v2", ignoreCase = true) -> "SD G3 Gen 3"
                    else -> result.gpuModel
                }
            )
        } else if (!result.chipId.isNullOrBlank()) {
            val chipIdLong = if (result.chipId.startsWith("0x", true)) result.chipId.substring(2).toLongOrNull(16) else result.chipId.toLongOrNull()
            if (chipIdLong != null) {
                val core = ((chipIdLong ushr 24) and 0xFF).toInt()
                val major = ((chipIdLong ushr 16) and 0xFF).toInt()
                val minor = ((chipIdLong ushr 8) and 0xFF).toInt()
                parts.add("Adreno $core$major$minor")
            }
        }
        
        if (!result.detectedModel.isNullOrBlank()) {
            val shortModel = result.detectedModel
                .replace("Qualcomm Technologies, Inc. ", "")
                .replace("Qualcomm Technologies, Inc ", "")
                .substringBefore("MTP").substringBefore("SoC").trim()
                .trimEnd(',')
            if (shortModel.isNotBlank()) {
                parts.add(shortModel)
            }
        }
        
        return when {
            parts.isEmpty() -> "Custom Device (DTB ${result.dtbIndex})"
            parts.size == 1 -> parts[0]
            else -> "${parts[0]} - ${parts[1]}"
        }
    }
}
