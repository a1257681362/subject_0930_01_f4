package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** 人工异常标记请求（只追加异常事件，不改动任何已有记录/凭证） */
public class ExceptionMarkRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    @NotBlank(message = "异常类型不能为空")
    private String exceptionType; // TEMP_EXCURSION / DAMAGE / DELAY / OTHER
    @NotBlank(message = "异常描述不能为空")
    private String description;
    private LocalDateTime occurredTime;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getOccurredTime() { return occurredTime; }
    public void setOccurredTime(LocalDateTime occurredTime) { this.occurredTime = occurredTime; }
}
