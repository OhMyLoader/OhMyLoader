package org.ohmyloader.core.mod

import java.io.File

data class ModContainer(
    val id: String,
    val name: String,
    val version: String,
    val entryClass: String,
    val file: File,
)
