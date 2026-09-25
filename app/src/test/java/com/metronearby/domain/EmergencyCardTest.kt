package com.metronearby.domain

import com.metronearby.data.EmergencyCodec
import com.metronearby.data.MetroJson
import org.junit.Assert.*
import org.junit.Test

class EmergencyCardTest {
    private val now = 1_800_000_000_000L
    private val point = UserLocation(39.9, 116.3, 20f, now)
    @Test fun acceptsHotline() { assertEquals("01096165", EmergencyCardPolicy.dialNumber("010-96165")) }
    @Test fun acceptsInternationalNumber() { assertEquals("+8613800000000", EmergencyCardPolicy.dialNumber("+86 (138) 0000-0000")) }
    @Test fun rejectsUssd() { assertNull(EmergencyCardPolicy.dialNumber("*123#")) }
    @Test fun rejectsUriInjection() { assertNull(EmergencyCardPolicy.dialNumber("123;456")) }
    @Test fun rejectsLetters() { assertNull(EmergencyCardPolicy.dialNumber("abc123")) }
    @Test fun rejectsTooShort() { assertNull(EmergencyCardPolicy.dialNumber("12")) }
    @Test fun rejectsTooLong() { assertNull(EmergencyCardPolicy.dialNumber("1".repeat(21))) }
    @Test fun optionalFieldsCanBeEmpty() { assertNull(EmergencyCardPolicy.error(EmergencyPersonal())) }
    @Test fun nameBoundary() { assertNull(EmergencyCardPolicy.error(EmergencyPersonal(contactName = "字".repeat(40)))) }
    @Test fun nameTooLong() { assertNotNull(EmergencyCardPolicy.error(EmergencyPersonal(contactName = "字".repeat(41)))) }
    @Test fun noteBoundary() { assertNull(EmergencyCardPolicy.error(EmergencyPersonal(note = "字".repeat(500)))) }
    @Test fun noteTooLong() { assertNotNull(EmergencyCardPolicy.error(EmergencyPersonal(note = "字".repeat(501)))) }
    @Test fun invalidPhoneBlocksSave() { assertNotNull(EmergencyCardPolicy.error(EmergencyPersonal(phone = "*123#"))) }
    @Test fun trimsText() { assertEquals("家人", EmergencyCardPolicy.clean(EmergencyPersonal(" 家人 ")).contactName) }
    @Test fun currentPointNotOutdated() { assertFalse(EmergencyCardPolicy.outdated(point, now)) }
    @Test fun oldPointOutdated() { assertTrue(EmergencyCardPolicy.outdated(point.copy(capturedAtMillis = now - 120001), now)) }
    @Test fun futurePointOutdated() { assertTrue(EmergencyCardPolicy.outdated(point.copy(capturedAtMillis = now + 10001), now)) }
    @Test fun rejectsInvalidCoordinates() { assertFalse(EmergencyCardPolicy.validLocation(point.copy(lat = 91.0))) }
    @Test fun rejectsNonfiniteCoordinates() { assertFalse(EmergencyCardPolicy.validLocation(point.copy(lng = Double.NaN))) }
    @Test fun rejectsNegativeAccuracy() { assertFalse(EmergencyCardPolicy.validLocation(point.copy(accuracyMeters = -1f))) }
    @Test fun locationRoundTrip() {
        assertEquals(point, EmergencyCodec.location(MetroJson.instance.encodeToString(UserLocation.serializer(), point)))
    }
    @Test fun personalRoundTrip() {
        val value = EmergencyPersonal("家人", "010-96165", "临时备注")
        assertEquals(value, EmergencyCodec.personal(MetroJson.instance.encodeToString(EmergencyPersonal.serializer(), value)))
    }
    @Test(expected = Exception::class) fun corruptedPersonalNotSilentlyCleared() { EmergencyCodec.personal("broken") }
    @Test fun routeSnapshotIncludesTransfer() {
        val route = OfflineRoutePlanner.RouteResult("起点", "终点", listOf(
            OfflineRoutePlanner.RouteLeg("a", "1号线", "#ff0000", "起点", "中间", listOf("起点", "中间")),
            OfflineRoutePlanner.RouteLeg("b", "2号线", "#0000ff", "中间", "终点", listOf("中间", "终点"))
        ))
        val snapshot = EmergencyCardPolicy.snapshot(route, now)
        assertTrue(snapshot.instructions.any { it.contains("在 中间 换乘 2号线") })
    }
    @Test fun routeRoundTrip() {
        val value = EmergencyRoute("起点", "终点", listOf("往下一站方向"), listOf("#ff0000"), now)
        assertEquals(value, EmergencyCodec.route(MetroJson.instance.encodeToString(EmergencyRoute.serializer(), value)))
    }
    @Test(expected = Exception::class) fun invalidRouteRejected() {
        val value = EmergencyRoute("", "", emptyList(), emptyList(), now)
        EmergencyCodec.route(MetroJson.instance.encodeToString(EmergencyRoute.serializer(), value))
    }
}
