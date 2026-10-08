package com.laioffer.onlineorder.repository;


// LIKE / ILIKE 模糊匹配的参数
public final class LikePattern {


    private LikePattern() {
    }


    // "包含 keyword" 的匹配串 %keyword%。用户输入里的 % 和 _ 是 LIKE 通配符，要转义成普通字符（PG 默认转义符是反斜杠）；
    // keyword 为空串时得到 %，匹配全部
    public static String contains(String keyword) {
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
