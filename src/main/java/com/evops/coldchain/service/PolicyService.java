package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.ColdChainPolicy;
import com.evops.coldchain.mapper.ColdChainPolicyMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 冷链药品运输权限策略（单行开关 cold-chain-transport）。
 *
 * ENABLED 放开：分仓交接正常推进；
 * RESTRICTED 收紧：拆单/封签核验/完成交接/放行派送一律停止推进，
 * 卡口 {@link #requireHandoverAllowed()} 返回收紧原因；已经确认的封签核验事实不回滚、不删除。
 */
@Service
public class PolicyService {

    @Resource
    private ColdChainPolicyMapper policyMapper;
    @Resource
    private OpLogService opLogService;

    /** 读取当前冷链运输权限策略（缺省视为放开，schema 已种入 ENABLED 行兜底） */
    public ColdChainPolicy current() {
        ColdChainPolicy policy = policyMapper.selectOne(new QueryWrapper<ColdChainPolicy>()
                .eq("policy_key", CcConstants.POLICY_KEY_COLD_CHAIN));
        if (policy == null) {
            // 极端情况下种子行缺失：不放行业务写操作，要求显式放开，避免“无策略即放行”
            throw new BusinessException("冷链药品运输权限策略缺失，分仓交接已停止推进；请先放开权限");
        }
        return policy;
    }

    public boolean isHandoverAllowed() {
        return CcConstants.POLICY_ENABLED.equals(current().getStatus());
    }

    /**
     * 分仓交接推进卡口：权限收紧时停止推进，并给出具体收紧原因。
     * 只拦截“推进类”动作；查询与已确认事实保留不受影响。
     */
    public void requireHandoverAllowed() {
        ColdChainPolicy policy = current();
        if (!CcConstants.POLICY_ENABLED.equals(policy.getStatus())) {
            throw new BusinessException(String.format(
                    "冷链药品运输权限已收紧（%s），分仓交接停止推进；已确认的封签核验事实保留，放开权限后可继续",
                    policy.getReason() == null ? "未说明原因" : policy.getReason()));
        }
    }

    /** 收紧冷链药品运输权限：之后分仓交接停止推进（已确认事实保留） */
    @Transactional
    public ColdChainPolicy restrict(String reason) {
        ColdChainPolicy policy = current();
        if (CcConstants.POLICY_RESTRICTED.equals(policy.getStatus())) {
            throw new BusinessException("冷链药品运输权限已处于收紧状态，不能重复收紧");
        }
        policy.setStatus(CcConstants.POLICY_RESTRICTED);
        policy.setReason(reason);
        policyMapper.updateById(policy);

        opLogService.log(CcConstants.ENT_POLICY, policy.getId(), null,
                CcConstants.ACT_POLICY_RESTRICT,
                "冷链药品运输权限收紧：分仓交接停止推进；已确认的封签核验事实保留。原因：" + reason);
        return policy;
    }

    /** 放开冷链药品运输权限：可凭此前已确认的封签核验事实继续推进 */
    @Transactional
    public ColdChainPolicy enable(String reason) {
        ColdChainPolicy policy = current();
        if (CcConstants.POLICY_ENABLED.equals(policy.getStatus())) {
            throw new BusinessException("冷链药品运输权限已处于放开状态，不能重复放开");
        }
        policy.setStatus(CcConstants.POLICY_ENABLED);
        policy.setReason(reason);
        policyMapper.updateById(policy);

        opLogService.log(CcConstants.ENT_POLICY, policy.getId(), null,
                CcConstants.ACT_POLICY_ENABLE,
                "冷链药品运输权限放开：分仓交接恢复推进，此前已确认的封签核验事实继续有效。原因：" + reason);
        return policy;
    }
}
