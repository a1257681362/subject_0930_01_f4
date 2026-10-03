package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 封签核验事实（只追加）：每个封签号每次核验一行，记录封签号、箱数、核验结论
 * （MATCH 相符 / MISMATCH 不符）、核验人与时间。
 * 应用层只有 INSERT 路径：冷链权限收紧、交接停止推进、批次撤回都不改写、不删除已确认事实。
 */
@TableName("t_cc_handover_seal_verify")
public class HandoverSealVerify extends CreateOnlyEntity {
    private Long handoverId;
    private Long batchId;
    private String sealNo;
    private Integer boxQty;
    /** MATCH 封签相符已确认 / MISMATCH 封签异常不符 */
    private String result;
    private String note;
    private String verifiedBy;
    private LocalDateTime verifyTime;

    public Long getHandoverId() { return handoverId; }
    public void setHandoverId(Long handoverId) { this.handoverId = handoverId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getSealNo() { return sealNo; }
    public void setSealNo(String sealNo) { this.sealNo = sealNo; }
    public Integer getBoxQty() { return boxQty; }
    public void setBoxQty(Integer boxQty) { this.boxQty = boxQty; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }
    public LocalDateTime getVerifyTime() { return verifyTime; }
    public void setVerifyTime(LocalDateTime verifyTime) { this.verifyTime = verifyTime; }
}
