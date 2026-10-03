package com.evops.coldchain.controller;

import com.evops.coldchain.dto.PolicyUpdateRequest;
import com.evops.coldchain.entity.ColdChainPolicy;
import com.evops.coldchain.service.PolicyService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 冷链药品运输权限策略：ENABLED 放开 / RESTRICTED 收紧。
 * 收紧后分仓交接推进类动作（拆单/封签核验/完成交接/放行派送）一律停止，
 * 但已经确认的封签核验事实保留；放开后凭已确认事实继续推进。
 */
@RestController
@RequestMapping("/api/cold-chain/policy")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    /** 当前冷链药品运输权限策略 */
    @GetMapping
    public ApiResponse<ColdChainPolicy> current() {
        return ApiResponse.ok(policyService.current());
    }

    /** 收紧权限：分仓交接停止推进（已确认封签事实保留） */
    @PostMapping("/restrict")
    public ApiResponse<ColdChainPolicy> restrict(@Valid @RequestBody PolicyUpdateRequest req) {
        return ApiResponse.ok(policyService.restrict(req.getReason()));
    }

    /** 放开权限：分仓交接恢复推进 */
    @PostMapping("/enable")
    public ApiResponse<ColdChainPolicy> enable(@Valid @RequestBody PolicyUpdateRequest req) {
        return ApiResponse.ok(policyService.enable(req.getReason()));
    }
}
