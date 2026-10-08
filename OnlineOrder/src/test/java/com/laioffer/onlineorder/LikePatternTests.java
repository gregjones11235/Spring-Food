package com.laioffer.onlineorder;


import com.laioffer.onlineorder.repository.LikePattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class LikePatternTests {


    @Test
    void contains_shouldWrapWithWildcards() {
        Assertions.assertEquals("%pizza%", LikePattern.contains("pizza"));
    }


    @Test
    void contains_emptyKeyword_shouldMatchAll() {
        Assertions.assertEquals("%%", LikePattern.contains(""));
    }


    // 用户输入的 % 和 _ 要当普通字符：搜 "100%" 不能匹配所有以 100 开头的名字
    @Test
    void contains_shouldEscapeWildcardsAndBackslash() {
        Assertions.assertEquals("%100\\%%", LikePattern.contains("100%"));
        Assertions.assertEquals("%a\\_b%", LikePattern.contains("a_b"));
        Assertions.assertEquals("%a\\\\b%", LikePattern.contains("a\\b"));
    }
}
