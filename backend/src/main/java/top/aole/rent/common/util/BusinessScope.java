package top.aole.rent.common.util;

import top.aole.rent.common.exception.BizException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务范围(货架/阁楼/播种墙)多选口径:落库为固定顺序的逗号分隔串。供应商考察、客户共用。
 */
public final class BusinessScope {

    public static final List<String> VALUES = Collections.unmodifiableList(Arrays.asList("货架", "阁楼", "播种墙"));

    private BusinessScope() {
    }

    /** 校验并按固定顺序拼成逗号串;空选返回 null。非法取值 → 400。 */
    public static String join(List<String> scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        Set<String> picked = new LinkedHashSet<>();
        for (String s : scope) {
            String t = s == null ? "" : s.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (!VALUES.contains(t)) {
                throw new BizException(400, "业务范围取值非法: " + t + ",应为 " + String.join("/", VALUES));
            }
            picked.add(t);
        }
        return picked.isEmpty() ? null
                : VALUES.stream().filter(picked::contains).collect(Collectors.joining(","));
    }

    /** 逗号串拆回列表;空 → 空列表。 */
    public static List<String> split(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(scope.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
