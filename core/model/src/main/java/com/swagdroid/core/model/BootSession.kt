package com.swagdroid.core.model

private val kernelBootPattern = Regex("^kernel:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

fun isValidBootId(value: String?): Boolean = value != null && kernelBootPattern.matches(value)

/** BOOT_COUNT / wall-clock changes / app restarts are not proof that old OS processes exited. */
fun isDifferentBoot(previous: String?, current: String?): Boolean =
    isValidBootId(previous) && isValidBootId(current) && !previous.equals(current, ignoreCase = true)
