package com.evops.coldchain.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

/**
 * 分仓拆单请求：一车货到达中转仓后，把一个运输批次按箱拆成多个交接单。
 * 提交即一次性强校验（任一不满足整体拒绝，并在 message 给出具体原因）：
 * 1) 各单箱数合计必须等于批次派车时固化的总箱数（箱数不平禁止提交）；
 * 2) 封签号在本批次内不得重复（封签重复禁止提交）；
 * 3) 每单交接温度、接收人不得缺失（温度缺失禁止提交）。
 */
public class HandoverSplitRequest {

    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;

    @NotBlank(message = "中转仓名称不能为空")
    private String warehouseName;

    @NotEmpty(message = "至少要有一个交接单")
    @Valid
    private List<Item> items;

    /** 一个交接单：箱数 + 封签号 + 交接温度 + 接收人 */
    public static class Item {
        @NotNull(message = "交接箱数不能为空")
        @Positive(message = "交接箱数必须大于0")
        private Integer boxQty;

        @NotBlank(message = "封签号不能为空")
        private String sealNo;

        @NotNull(message = "交接温度不能为空（温度缺失禁止提交）")
        private BigDecimal handoverTemperature;

        @NotBlank(message = "接收人不能为空")
        private String receiverName;

        private String receiverPhone;

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
    }

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }
}
