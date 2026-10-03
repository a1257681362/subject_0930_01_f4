package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 冷链药品运输权限调整请求（收紧 /restrict、放开 /enable 各用一个端点，故只需原因）。
 * RESTRICTED 收紧后分仓交接停止推进（卡口返回 reason），但已经确认的封签核验事实原样保留。
 */
public class PolicyUpdateRequest {

    @NotBlank(message = "调整原因不能为空（收紧停点提示将回带该原因）")
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
