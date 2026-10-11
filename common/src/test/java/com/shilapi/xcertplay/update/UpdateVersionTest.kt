package com.shilapi.xcertplay.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun newerTagIsAnUpdate() {
        assertTrue(UpdateVersion.isNewer("v0.2.15", "0.2.14"))
    }

    @Test
    fun sameVersionIsNotAnUpdate() {
        assertFalse(UpdateVersion.isNewer("0.2.14", "0.2.14"))
    }

    @Test
    fun cnRevisionAdvancesWithinAnUpstreamPatch() {
        assertTrue(UpdateVersion.isNewer("v0.2.17-cn.3", "0.2.17-cn.2"))
        assertFalse(UpdateVersion.isNewer("v0.2.17-cn.1", "0.2.17-cn.2"))
        assertFalse(UpdateVersion.isNewer("v0.2.17", "0.2.17-cn.2"))
    }

    @Test
    fun comparesHistoricalFourComponentVersionsBeforeCnRevisions() {
        assertTrue(UpdateVersion.isNewer("v0.2.8.5-cn.1", "0.2.8.4-cn.9"))
        assertFalse(UpdateVersion.isNewer("v0.2.8.4-cn.9", "0.2.8.5-cn.1"))
    }

    @Test
    fun olderTagIsIgnored() {
        assertFalse(UpdateVersion.isNewer("v0.2.13", "0.2.14"))
    }

    @Test
    fun numericFieldsCompareLeftToRight() {
        assertTrue(UpdateVersion.isNewer("0.3.0", "0.2.14"))
        assertFalse(UpdateVersion.isNewer("0.2.9", "0.2.14"))
    }

    @Test
    fun preReleaseSuffixStillComparesByNumbers() {
        assertTrue(UpdateVersion.isNewer("0.2.15-beta.1", "0.2.14"))
        assertFalse(UpdateVersion.isNewer("0.2.14-rc.1", "0.2.14"))
    }

    @Test
    fun unparseableRemoteVersionIsNeverAnUpdate() {
        assertFalse(UpdateVersion.isNewer("public-preview", "0.2.14"))
    }

    @Test
    fun unparseableInstalledVersionAcceptsAnyParseableRemote() {
        assertTrue(UpdateVersion.isNewer("0.2.15", "psa.2"))
        assertFalse(UpdateVersion.isNewer("public-preview", "psa.2"))
    }
}
