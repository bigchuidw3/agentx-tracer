package com.agentx.tracer.common.enums;

/**
 * 数据范围枚举（角色绑定，控制用户能看多大范围的数据）。
 * <p>
 * 优先级（ordinal 越小范围越大）：ALL > DEPT_AND_SUB > DEPT > SELF
 * <p>
 * 多角色合并：取最大范围（ordinal 最小的那个）。
 */
public enum DataScope {

    /**
     * 全部数据，不注入 WHERE
     */
    ALL,

    /**
     * 本部门及子部门，注入 dept_id IN (...)
     */
    DEPT_AND_SUB,

    /**
     * 仅本部门，注入 dept_id IN (...)
     */
    DEPT,

    /**
     * 仅本人，注入 user_id = ?
     */
    SELF;

    /**
     * 字符串安全解析，失败返回 SELF（fail-closed：最小权限）。
     */
    public static DataScope fromString(String s) {
        if (s == null || s.isBlank()) {
            return SELF;
        }
        try {
            return DataScope.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SELF;
        }
    }

    /**
     * 返回范围更大的那个（ordinal 越小范围越大）。null 视为 SELF。
     */
    public static DataScope max(DataScope a, DataScope b) {
        DataScope sa = a == null ? SELF : a;
        DataScope sb = b == null ? SELF : b;
        return sa.ordinal() <= sb.ordinal() ? sa : sb;
    }
}
