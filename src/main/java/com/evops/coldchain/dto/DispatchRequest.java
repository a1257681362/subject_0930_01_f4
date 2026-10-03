package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/** 派车请求：发货单 + 承运车辆，生成运输批次并固化档案快照 */
public class DispatchRequest {
    @NotNull(message = "发货单ID不能为空")
    private Long orderId;
    @NotNull(message = "承运车辆ID不能为空")
    private Long vehicleId;
    /**
     * 可选运输路线ID：传入则按派车时点的季节选取该路线生效版本，逐段固化温区分段快照。
     * 不传则保持 V1 全程单一档案温区口径。
     */
    private Long routeId;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
}
