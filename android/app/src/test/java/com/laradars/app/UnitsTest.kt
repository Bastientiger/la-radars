package com.laradars.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    private val us = Units(metric = false)
    private val metric = Units(metric = true)

    @Test
    fun speeds() {
        assertEquals(60, us.speed(26.82f))      // 26.82 m/s = 60 mph
        assertEquals(97, metric.speed(26.82f))  // = 96.6 km/h
        assertEquals("35 mph", us.limit(35))
        assertEquals("35 mph · 56 km/h", metric.limit(35))
    }

    @Test
    fun distances() {
        assertEquals("350 m", metric.distance(350.0))
        assertEquals("1.2 km", metric.distance(1234.0))
        assertEquals("328 ft", us.distance(100.0))
        assertEquals("0.6 mi", us.distance(1000.0))
    }

    @Test
    fun spokenDistances() {
        assertEquals("350 metres", metric.spokenDistance(340.0))
        assertEquals("500 feet", us.spokenDistance(150.0))
        assertEquals("a quarter mile", us.spokenDistance(400.0))
        assertEquals("half a mile", us.spokenDistance(800.0))
    }
}
