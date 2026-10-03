package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/**
 * 封签核验请求：对某一交接单的封签进行核验，事实只追加一行。
 * result=true 表示封签相符（MATCH，已确认事实，之后权限收紧也保留）；
 * result=false 表示封签异常不符（MISMATCH，留痕但该单不能完成交接/继续派送）。
 */
public class SealVerifyRequest {

    @NotNull(message = "交接单ID不能为空")
    private Long handoverId;

    /** 本次核验确认的箱数（不传则按交接单声明箱数记录） */
    private Integer boxQty;

    @NotNull(message = "核验结论不能为空：true=封签相符 MATCH，false=封签不符 MISMATCH")
    private Boolean match;

    private String note;

    public Long getHandoverId() { return handoverId; }
    public void setHandoverId(Long handoverId) { this.handoverId = handoverId; }
    public Integer getBoxQty() { return boxQty; }
    public void setBoxQty(Integer boxQty) { this.boxQty = boxQty; }
    public Boolean getMatch() { return match; }
    public void setMatch(Boolean match) { this.match = match; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
