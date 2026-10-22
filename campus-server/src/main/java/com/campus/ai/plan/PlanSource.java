package com.campus.ai.plan;

/**
 * P0.5/P1.5 推荐来源标签：方案的地点究竟来自哪里，必须可被后端按数据核实，
 * 不由模型自述。校验器在提交时会按地点归属覆写这些标签（不信任模型给的值）。
 */
public final class PlanSource {

    /** 当前群体共建地图（plan.sourceMapIds 关联的共享地图） */
    public static final String GROUP_MAP = "GROUP_MAP";
    /** 用户个人可访问地图（未关联当前群，需用户明确扩大范围） */
    public static final String PERSONAL_MAP = "PERSONAL_MAP";
    /** 高德外部 POI（无系统 placeId，纯坐标站点） */
    public static final String AMAP_POI = "AMAP_POI";
    /** 规划当次的实时起点/返程点：只存在于草稿与会话，不写入 place / activity_stop */
    public static final String USER_LOCATION = "USER_LOCATION";

    private PlanSource() {
    }
}
