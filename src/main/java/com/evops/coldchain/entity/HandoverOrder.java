package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分仓交接单：一车货到达中转仓后可把一个运输批次拆成多个交接单。
 * 每单记录箱数、封签号、交接温度与接收人；交接完成后责任主体随单转移
 * （handoverOwner* 固化为接收仓/接收人），原运输批次仍可追溯全部节点。
 * 状态：PENDING 待封签核验 / SEAL_VERIFIED 封签已核验待交接 /
 * COMPLETED 交接完成责任已转移 / ABANDONED 批次撤回连带放弃（原始行保留）。
 */
@TableName("t_cc_handover_order")
public class HandoverOrder extends BaseEntity {
    private String handoverNo;
    private Long batchId;
    private String warehouseName;
    private Integer boxQty;
    private String sealNo;
    private BigDecimal handoverTemperature;
    private String receiverName;
    private String receiverPhone;

    /** 交接完成时间（责任转移时点） */
    private LocalDateTime handoverTime;
    /** 交接后责任主体（接收仓） */
    private String handoverOwnerOrg;
    /** 交接后责任人（接收人快照） */
    private String handoverOwnerName;
    /** 核验完成后是否已放行下游派送 */
    private Boolean deliveredFlag;
    private LocalDateTime deliveredTime;

    private String status;
    private String remark;

    public String getHandoverNo() { return handoverNo; }
    public void setHandoverNo(String handoverNo) { this.handoverNo = handoverNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }
    public Integer getBoxQty() { return boxQty; }
    public void setBoxQty(Integer boxQty) { this.boxQty = boxQty; }
    public String getSealNo() { return sealNo; }
    public void setSealNo(String sealNo) { this.sealNo = sealNo; }
    public BigDecimal getHandoverTemperature() { return handoverTemperature; }
    public void setHandoverTemperature(BigDecimal handoverTemperature) { this.handoverTemperature = handoverTemperature; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getReceiverPhone() { return receiverPhone; }
    public void setReceiverPhone(String receiverPhone) { this.receiverPhone = receiverPhone; }
    public LocalDateTime getHandoverTime() { return handoverTime; }
    public void setHandoverTime(LocalDateTime handoverTime) { this.handoverTime = handoverTime; }
    public String getHandoverOwnerOrg() { return handoverOwnerOrg; }
    public void setHandoverOwnerOrg(String handoverOwnerOrg) { this.handoverOwnerOrg = handoverOwnerOrg; }
    public String getHandoverOwnerName() { return handoverOwnerName; }
    public void setHandoverOwnerName(String handoverOwnerName) { this.handoverOwnerName = handoverOwnerName; }
    public Boolean getDeliveredFlag() { return deliveredFlag; }
    public void setDeliveredFlag(Boolean deliveredFlag) { this.deliveredFlag = deliveredFlag; }
    public LocalDateTime getDeliveredTime() { return deliveredTime; }
    public void setDeliveredTime(LocalDateTime deliveredTime) { this.deliveredTime = deliveredTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
