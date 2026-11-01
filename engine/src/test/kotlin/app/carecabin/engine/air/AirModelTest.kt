package app.carecabin.engine.air

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirModelTest {
    @Test fun `two occupants after thirty minutes match mass balance analytical solution`() {
        val input = AirInputs(occupantCount = 2, airChangesPerHourOverride = 1.0)
        val model = AirModel()
        val exact = model.analyticalAfter(input, 1800.0)
        repeat(1800) { model.step(input) }
        assertTrue(model.ppm in 5000.0..5600.0)
        assertEquals(exact, model.ppm, exact * 0.01)
    }
    @Test fun `fresh air at fan three matches analytical equilibrium for four occupants`() {
        val input = AirInputs(occupantCount = 4, recirculating = false, fanLevel = 3)
        val model = AirModel()
        repeat(3600) { model.step(input) }
        val steady = AirConstants.OUTDOOR_PPM + input.generationPpmPerSecond / input.lambdaPerSecond
        assertEquals(1133.14, steady, 0.1)
        assertEquals(steady, model.ppm, 0.1)
    }
    @Test fun `fresh air at fan five with four occupants converges below nine hundred`() {
        val model = AirModel()
        repeat(3600) { model.step(AirInputs(occupantCount = 4, recirculating = false, fanLevel = 5)) }
        assertTrue(model.ppm < 900)
    }
    @Test fun `threshold prediction is within three seconds of integration`() {
        val input = AirInputs(occupantCount = 2)
        val model = AirModel()
        val predicted = model.secondsUntil(1000.0, input)
        var elapsed = 0
        while (model.ppm < 1000 && elapsed < 3600) { model.step(input); elapsed++ }
        assertEquals(predicted, elapsed.toDouble(), 3.0)
    }
    @Test fun `unreachable threshold does not report a finite arrival`() {
        assertEquals(Double.POSITIVE_INFINITY, AirModel().secondsUntil(1500.0,
            AirInputs(occupantCount = 1, recirculating = false, fanLevel = 8)))
    }
    @Test fun `empty cabin decays toward outdoor air without falling below it`() {
        val model = AirModel()
        repeat(3600) { model.step(AirInputs(occupantCount = 0, recirculating = false, fanLevel = 8)) }
        assertTrue(model.ppm >= AirConstants.OUTDOOR_PPM && model.ppm < 421)
    }
    @Test fun `zero ventilation handles linear accumulation and no occupants`() {
        assertEquals(400.0 / (2 * 5.2 / 3), AirModel().secondsUntil(1000.0,
            AirInputs(occupantCount = 2, airChangesPerHourOverride = 0.0)), 0.001)
        assertEquals(Double.POSITIVE_INFINITY, AirModel().secondsUntil(1000.0,
            AirInputs(occupantCount = 0, airChangesPerHourOverride = 0.0)))
    }
    @Test fun `invalid physical inputs and long integration gaps are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AirInputs(occupantCount = -1) }
        assertThrows(IllegalArgumentException::class.java) { AirInputs(occupantCount = 1, volumeM3 = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { AirInputs(occupantCount = 1, fanLevel = 9) }
        assertThrows(IllegalArgumentException::class.java) { AirModel().step(AirInputs(1), 60.0) }
    }
}
