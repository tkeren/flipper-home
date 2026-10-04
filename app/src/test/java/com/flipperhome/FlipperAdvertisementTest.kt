package com.flipperhome

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FlipperAdvertisementTest {
    @Test fun unnamedSerialAdvertisementsAreRecognizedAcrossHardwareColors() {
        listOf("00003080-0000-1000-8000-00805f9b34fb", "00003081-0000-1000-8000-00805f9b34fb", "00003082-0000-1000-8000-00805f9b34fb").forEach {
            assertTrue(FlipperAdvertisement.isCandidate(null, "C0:11:22:33:44:55", listOf(UUID.fromString(it))))
        }
    }
    @Test fun officialMacRuleWorksWithoutNameOrServiceData() {
        assertTrue(FlipperAdvertisement.isCandidate(null, "80:E1:26:11:22:33", emptyList()))
    }
    @Test fun namedFlippersRemainCandidatesWithPrivateAddresses() {
        assertTrue(FlipperAdvertisement.isCandidate("Flipper Oridafle", "C0:11:22:33:44:55", emptyList()))
    }
    @Test fun commonDeviceInformationServiceDoesNotIdentifyFlipper() {
        assertFalse(FlipperAdvertisement.isCandidate(null, "C0:11:22:33:44:55", listOf(UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"))))
        assertFalse(FlipperAdvertisement.isCandidate("Other device", "C0:11:22:33:44:55", emptyList()))
    }
}
