package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 冷链权限策略（单行，policy_key=cold-chain-transport）。
 * ENABLED 放开：分仓交接正常推进；RESTRICTED 收紧：拆单/封签核验/完成交接/放行派送
 * 一律停止推进（卡口返回收紧原因），但已确认的封签核验事实原样保留。
 */
@TableName("t_cc_policy")
public class ColdChainPolicy extends BaseEntity {
    private String policyKey;
    /** ENABLED / RESTRICTED */
    private String status;
    /** 收紧/放开原因（卡口停点提示以此为准） */
    private String reason;

    public String getPolicyKey() { return policyKey; }
    public void setPolicyKey(String policyKey) { this.policyKey = policyKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
