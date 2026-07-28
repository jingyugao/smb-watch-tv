package com.smbwatch.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NaturalOrderTest {

    @Test
    public void numbersCompareByValueNotLexically() {
        assertTrue(NaturalOrder.compare("第2集.mkv", "第10集.mkv") < 0);
        assertTrue(NaturalOrder.compare("ep9.mp4", "ep11.mp4") < 0);
        assertTrue(NaturalOrder.compare("S01E02", "S01E10") < 0);
    }

    @Test
    public void leadingZerosCompareEqualValueThenLength() {
        assertEquals(0, NaturalOrder.compare("ep01", "ep01"));
        assertTrue(NaturalOrder.compare("ep1", "ep01") < 0);
        assertTrue(NaturalOrder.compare("ep01", "ep2") < 0);
    }

    @Test
    public void lettersCompareCaseInsensitively() {
        assertEquals(0, NaturalOrder.compare("Movie", "movie"));
        assertTrue(NaturalOrder.compare("abc", "abd") < 0);
    }

    @Test
    public void shorterPrefixComesFirst() {
        assertTrue(NaturalOrder.compare("ep", "ep1") < 0);
    }

    @Test
    public void nullsSortFirst() {
        assertEquals(0, NaturalOrder.compare(null, null));
        assertTrue(NaturalOrder.compare(null, "a") < 0);
        assertTrue(NaturalOrder.compare("a", null) > 0);
    }
}
