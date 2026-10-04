package com.flipperhome

import java.util.UUID

/** Candidate detection from official app MAC/name rules and firmware serial advertisements.
 * Identification is confirmed later by discovering the authenticated RPC service.
 */
object FlipperAdvertisement {
    private val serialService = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
    fun isCandidate(name: String?, address: String, services: List<UUID>): Boolean {
        if (name?.startsWith("Flipper", ignoreCase = true) == true) return true
        if (address.startsWith("80:E1:26:", ignoreCase = true)) return true
        return services.any { uuid ->
            if (uuid == serialService) true else {
                val text = uuid.toString()
                // Firmware advertises 0x3080 OR hardware color as a Bluetooth 16-bit UUID.
                text.endsWith("-0000-1000-8000-00805f9b34fb") &&
                    (text.substring(0, 8).toLong(16) and 0xfffffff0L) == 0x3080L
            }
        }
    }
}
