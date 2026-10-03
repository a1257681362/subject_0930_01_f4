package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 异常事件：追加式，一批可多次。
 * 原描述 description 一旦写入永不修改；处理闭环只填 resolve* 字段。
 */
@TableName("t_cc_exception")
public class ExceptionEvent extends BaseEntity {
    private Long batchId;
    /** TEMP_EXCURSION / DAMAGE / DELAY / OTHER */
    private String exceptionType;
    private String description;
    private LocalDateTime occurredTime;
    /** OPEN / RESOLVED */
    private String status;
    private String resolveNote;
    private Long resolveBy;
    private LocalDateTime resolveTime;
    /** 越限命中的路线分段序号（有路线分段时），便于追溯该段责任人 */
    private Integer segmentSeq;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getOccurredTime() { return occurredTime; }
    public void setOccurredTime(LocalDateTime occurredTime) { this.occurredTime = occurredTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResolveNote() { return resolveNote; }
    public void setResolveNote(String resolveNote) { this.resolveNote = resolveNote; }
    public Long getResolveBy() { return resolveBy; }
    public void setResolveBy(Long resolveBy) { this.resolveBy = resolveBy; }
    public LocalDateTime getResolveTime() { return resolveTime; }
    public void setResolveTime(LocalDateTime resolveTime) { this.resolveTime = resolveTime; }
    public Integer getSegmentSeq() { return segmentSeq; }
    public void setSegmentSeq(Integer segmentSeq) { this.segmentSeq = segmentSeq; }
}
