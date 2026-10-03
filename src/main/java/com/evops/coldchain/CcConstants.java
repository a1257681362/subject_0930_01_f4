package com.evops.coldchain;

/** 冷链台账状态与动作常量 */
public final class CcConstants {

    private CcConstants() {
    }

    // 档案/车辆状态
    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    // 档案版本状态
    public static final String EFFECTIVE = "EFFECTIVE";
    public static final String SUPERSEDED = "SUPERSEDED";

    // 路线季节版本状态（DRAFT 尚未发布 / EFFECTIVE 生效中 / SUPERSEDED 被热替换 / REVOKED 已撤回）
    public static final String ROUTE_DRAFT = "DRAFT";
    // EFFECTIVE / SUPERSEDED / REVOKED 复用上方常量

    // 季节（同一路线可按季节各维护一套版本；GENERAL 为不区分季节的通用配置）
    public static final String SEASON_SPRING = "SPRING";
    public static final String SEASON_SUMMER = "SUMMER";
    public static final String SEASON_AUTUMN = "AUTUMN";
    public static final String SEASON_WINTER = "WINTER";
    public static final String SEASON_GENERAL = "GENERAL";

    // 发货单状态
    public static final String ORDER_CREATED = "CREATED";
    public static final String ORDER_DISPATCHED = "DISPATCHED";
    public static final String ORDER_SHIPPED = "SHIPPED";
    public static final String ORDER_SIGNED = "SIGNED";
    public static final String ORDER_REVOKED = "REVOKED";

    // 运输批次状态
    public static final String BATCH_DISPATCHED = "DISPATCHED";
    public static final String BATCH_SHIPPED = "SHIPPED";
    public static final String BATCH_EXCEPTION = "EXCEPTION";
    public static final String BATCH_SIGNED = "SIGNED";
    public static final String BATCH_REVOKED = "REVOKED";

    // 异常
    public static final String EXC_TEMP = "TEMP_EXCURSION";
    public static final String EXC_DAMAGE = "DAMAGE";
    public static final String EXC_DELAY = "DELAY";
    public static final String EXC_OTHER = "OTHER";
    public static final String EXC_OPEN = "OPEN";
    public static final String EXC_RESOLVED = "RESOLVED";

    // 操作日志实体类型
    public static final String ENT_DRUG = "DRUG";
    public static final String ENT_VEHICLE = "VEHICLE";
    public static final String ENT_ORDER = "ORDER";
    public static final String ENT_BATCH = "BATCH";
    public static final String ENT_TEMPERATURE = "TEMPERATURE";
    public static final String ENT_EXCEPTION = "EXCEPTION";
    public static final String ENT_ROUTE = "ROUTE";
    // 车载温度探头 / 校准凭证
    public static final String ENT_PROBE = "PROBE";
    public static final String ENT_CERT = "CERT";

    // 校准凭证状态（VALID 有效 / VOID 作废；只追加 void* 字段，不删除原行）
    public static final String CERT_VALID = "VALID";
    public static final String CERT_VOID = "VOID";

    // 操作动作
    public static final String ACT_CREATE = "CREATE";
    public static final String ACT_CHANGE = "CHANGE";
    public static final String ACT_DISPATCH = "DISPATCH";
    public static final String ACT_SHIP = "SHIP";
    public static final String ACT_TEMP_REPORT = "TEMP_REPORT";
    public static final String ACT_EXCEPTION_MARK = "EXCEPTION_MARK";
    public static final String ACT_EVIDENCE_APPEND = "EVIDENCE_APPEND";
    public static final String ACT_EXCEPTION_RESOLVE = "EXCEPTION_RESOLVE";
    public static final String ACT_SIGN = "SIGN";
    public static final String ACT_REVOKE = "REVOKE";
    // 路线温区分段：草稿编排 / 发布（热替换）/ 撤回未生效版本 / 发布前差异核对
    public static final String ACT_ROUTE_DRAFT = "ROUTE_DRAFT";
    public static final String ACT_ROUTE_PUBLISH = "ROUTE_PUBLISH";
    public static final String ACT_ROUTE_WITHDRAW = "ROUTE_WITHDRAW";
    public static final String ACT_ROUTE_DIFF = "ROUTE_DIFF";
    // 车载探头：建档 / 证书上传 / 证书下载 / 证书作废 / 凭证关联（绑定/替换）
    public static final String ACT_PROBE_CREATE = "PROBE_CREATE";
    public static final String ACT_CERT_UPLOAD = "CERT_UPLOAD";
    public static final String ACT_CERT_DOWNLOAD = "CERT_DOWNLOAD";
    public static final String ACT_CERT_VOID = "CERT_VOID";
    public static final String ACT_PROBE_BIND = "PROBE_BIND";
    public static final String ACT_PROBE_REPLACE = "PROBE_REPLACE";
}
