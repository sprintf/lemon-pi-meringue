package com.normtronix.meringue

import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

internal class ContextInterceptorTest {

    @Test
    fun buildGoodContext() {
        val tracks = mock(TrackMetaDataLoader::class.java)
        val s = ContextInterceptor(tracks)
        `when`(tracks.isValidTrackCode("abcd")).thenReturn(true)
        val result = s.buildContext("abcd/181:foo", tracks)
        assertEquals("abcd", result?.trackCode)
        assertEquals("181", result?.carNum)
        assertEquals("foo", result?.key)
    }

    @Test
    fun buildContextWithBadTrack() {
        val tracks = mock(TrackMetaDataLoader::class.java)
        val s = ContextInterceptor(tracks)
        `when`(tracks.isValidTrackCode("bad")).thenReturn(false)
        assertNull(s.buildContext("bad/181:foo", tracks))
    }

    @Test
    fun buildContextWithBadString() {
        val tracks = mock(TrackMetaDataLoader::class.java)
        val s = ContextInterceptor(tracks)
        assertNull(s.buildContext("whatever", tracks))
        assertNull(s.buildContext("hello:goodbye", tracks))
        assertNull(s.buildContext("hob/goblin", tracks))
        assertNull(s.buildContext("hob/goblin:one:two", tracks))
    }
}