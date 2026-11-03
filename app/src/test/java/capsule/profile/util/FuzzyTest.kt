package capsule.profile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the fuzzy search that backs the app list: every scoring tier
 * (exact, prefix, word start, substring, subsequence), the multi-token AND
 * rule and the label-over-package tiebreak.
 */
class FuzzyTest {
    private fun score(
        label: String,
        pkg: String,
        query: String,
    ): Int = Fuzzy.score(label, pkg, query)

    @Test
    fun exactLabelBeatsEveryOtherTier() {
        val exact = score("maps", "com.example.maps", "maps")
        val prefix = score("maps navigator", "com.example.maps", "maps")
        val contains = score("city maps app", "com.example.city", "maps")
        val subsequence = score("my awesome photo storage", "com.example.x", "maps")
        assertTrue(exact > prefix)
        assertTrue(prefix > contains)
        assertTrue(contains > subsequence)
        assertTrue(subsequence > 0)
    }

    @Test
    fun exactLabelHasTheKnownScore() {
        // SCORE_EXACT is returned as-is; the shortness bonus only applies
        // to the weaker tiers.
        assertEquals(10000, score("maps", "com.example.maps", "maps"))
    }

    @Test
    fun prefixMatchOutscoresLaterWordStart() {
        val prefix = score("maps navigator", "com.example.a", "maps")
        val laterWord = score("city maps", "com.example.b", "maps")
        assertTrue(prefix > laterWord)
        assertTrue(laterWord > 0)
    }

    @Test
    fun wordStartRecognizesDotUnderscoreAndDashBoundaries() {
        // Package names are the natural word-boundary case.
        assertTrue(score("Some App", "com.company.maps.free", "maps") > 0)
        assertTrue(score("Some App", "com.company.maps_free", "free") > 0)
        assertTrue(score("some-app", "com.company.x", "app") > 0)
    }

    @Test
    fun substringMatchLosesGroundWithDepth() {
        val early = score("mapssomething", "com.a", "maps")
        val late = score("0123456789mapssomething", "com.a", "maps")
        assertTrue(early > late)
        assertTrue(late > 0)
    }

    @Test
    fun inOrderSubsequenceMatchesWithTheLowestTier() {
        // "m...a...p...s" in order, but nothing tighter.
        val s = score("mother apple sugar", "com.example.x", "maps")
        assertTrue("subsequence should match, got $s", s > 0)
        assertTrue(s < 4000)
    }

    @Test
    fun outOfOrderCharactersDoNotMatch() {
        assertEquals(0, score("spam", "com.example.x", "maps"))
    }

    @Test
    fun noSharedCharactersScoresZero() {
        assertEquals(0, score("Calculator", "com.example.calc", "zzz"))
    }

    @Test
    fun allQueryTokensMustMatch() {
        assertTrue(score("Maps Navigator", "com.example.maps", "maps nav") > 0)
        assertEquals(0, score("Maps Navigator", "com.example.maps", "maps nothing"))
    }

    @Test
    fun tokenScoresAddUp() {
        val single = score("maps", "com.example.maps", "maps")
        val multi = score("maps", "com.example.maps", "maps maps")
        assertEquals(single * 2, multi)
    }

    @Test
    fun labelMatchOutranksEquivalentPackageMatch() {
        val viaLabel = score("maps", "com.example.other", "maps")
        val viaPackage = score("unrelated", "com.example.maps", "maps")
        assertTrue("label ($viaLabel) should beat package ($viaPackage)", viaLabel > viaPackage)
    }

    @Test
    fun packageMatchStillBeatsNoMatch() {
        val viaPackage = score("unrelated", "com.example.maps", "maps")
        assertTrue(viaPackage > 0)
        assertTrue(viaPackage < 8000)
    }

    @Test
    fun packagePenaltyNeverDropsBelowOne() {
        // A long package name: the match lands deep (word start after the
        // dots), the shortness bonus is gone, and the package penalty
        // still keeps the score positive.
        val field = "a".repeat(300) + ".maps"
        val s = score("unrelated", field, "maps")
        assertTrue(s > 0)
    }

    @Test
    fun emptyAndBlankQueriesMatchEverything() {
        assertEquals(1, score("Anything", "com.example.x", ""))
        assertEquals(1, score("Anything", "com.example.x", "   "))
        assertEquals(1, score("Anything", "com.example.x", "\t"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(
            score("Maps", "com.example.Maps", "maps"),
            score("MAPS", "COM.EXAMPLE.MAPS", "MAPS"),
        )
    }

    @Test
    fun zeroScoreMeansNoMatch() {
        assertEquals(0, score("Maps", "com.example.maps", "xyz"))
        assertTrue(score("Maps", "com.example.maps", "map") > 0)
    }

    @Test
    fun repeatedTokensStillMatchAsSubsequences() {
        // Two "app" tokens, each satisfied by the same field.
        assertTrue(score("app", "com.example.app", "app app") > 0)
    }
}
