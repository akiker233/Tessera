package com.tessera.otp.data.icon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** assets/icon-index.json 的单个条目。 */
@Serializable
data class IconEntry(
    @SerialName("f") val file: String,
    @SerialName("l") val light: String? = null,
    @SerialName("d") val dark: String? = null,
    @SerialName("n") val displayName: String = ""
)

/** 整个索引：normalizeKey -> IconEntry。 */
@Serializable
data class IconIndex(@SerialName("v") val version: Int = 1, @SerialName("icons") val icons: Map<String, IconEntry>)
