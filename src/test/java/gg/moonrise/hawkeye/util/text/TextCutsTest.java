package gg.moonrise.hawkeye.util.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TextCutsTest {

    /** One code point, two chars. */
    private static final String PAIR = "😀";

    @Test
    public void aShortTextIsKept() {
        assertEquals("abc", TextCuts.head("abc", 3));
        assertEquals("abc", TextCuts.head("abc", 10));
        assertEquals("", TextCuts.head("", 0));
    }

    @Test
    public void aLongTextIsCutToTheLength() {
        assertEquals("ab", TextCuts.head("abcdef", 2));
        assertEquals("", TextCuts.head("abc", 0));
    }

    @Test
    public void aCutNeverSplitsASurrogatePair() {
        assertEquals("ab", TextCuts.head("ab" + PAIR + "c", 3));
        assertEquals("ab" + PAIR, TextCuts.head("ab" + PAIR + "c", 4));
        assertEquals("", TextCuts.head(PAIR, 1));
    }
}
