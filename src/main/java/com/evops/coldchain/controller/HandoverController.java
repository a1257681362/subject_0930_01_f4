package com.evops.coldchain.controller;

import com.evops.coldchain.dto.HandoverSplitRequest;
import com.evops.coldchain.dto.SealVerifyRequest;
import com.evops.coldchain.entity.HandoverOrder;
import com.evops.coldchain.entity.HandoverSealVerify;
import com.evops.coldchain.service.HandoverService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷链批次分仓交接：一车货到中转仓拆成多个交接单 -> 封签核验（事实只追加）
 * -> 完成交接（责任主体随单转移）-> 放行下游派送（未核验不放行）。
 * 冷链药品运输权限收紧时，推进类接口由服务层统一卡口停止，已确认核验事实保留。
 */
@RestController
@RequestMapping("/api/cold-chain/handovers")
public class HandoverController {

    private final HandoverService handoverService;

    public HandoverController(HandoverService handoverService) {
        this.handoverService = handoverService;
    }

    /** 分仓拆单：一车货 -> 多张交接单（箱数/封签/温度/接收人校验不通过返回具体原因并禁止提交） */
    @PostMapping("/split")
    public ApiResponse<List<HandoverOrder>> split(@Valid @RequestBody HandoverSplitRequest req) {
        return ApiResponse.ok(handoverService.split(req));
    }

    /** 封签核验（MATCH/MISMATCH 事实只追加，不改写历史核验行） */
    @PostMapping("/seal-verifications")
    public ApiResponse<HandoverSealVerify> verifySeal(@Valid @RequestBody SealVerifyRequest req) {
        return ApiResponse.ok(handoverService.verifySeal(req));
    }

    /** 完成交接：封签核验通过后责任主体随单转移 */
    @PostMapping("/{id}/complete")
    public ApiResponse<HandoverOrder> complete(@PathVariable("id") Long id) {
        return ApiResponse.ok(handoverService.complete(id));
    }

    /** 放行下游派送：未完成核验/未完成交接的箱子不能继续派送 */
    @PostMapping("/{id}/deliver")
    public ApiResponse<HandoverOrder> deliver(@PathVariable("id") Long id) {
        return ApiResponse.ok(handoverService.releaseForDelivery(id));
    }

    /** 交接单详情（含全部封签核验事实） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        HandoverOrder order = handoverService.requireHandover(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("handover", order);
        data.put("sealVerifies", handoverService.listVerifies(id));
        return ApiResponse.ok(data);
    }

    /** 某批次的全部交接单 */
    @GetMapping("/batch/{batchId}")
    public ApiResponse<List<HandoverOrder>> listByBatch(@PathVariable("batchId") Long batchId) {
        return ApiResponse.ok(handoverService.listByBatch(batchId));
    }

    /** 某交接单的封签核验流水（只追加事实，时间升序） */
    @GetMapping("/{id}/seal-verifications")
    public ApiResponse<List<HandoverSealVerify>> verifies(@PathVariable("id") Long id) {
        return ApiResponse.ok(handoverService.listVerifies(id));
    }
}
