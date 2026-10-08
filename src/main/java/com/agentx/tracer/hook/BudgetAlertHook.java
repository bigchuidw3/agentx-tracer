package com.agentx.tracer.hook;

import com.agentx.ai.core.hook.AfterCallEvent;
import com.agentx.ai.core.hook.AgentHook;
import com.agentx.ai.core.hook.HookEvent;
import com.agentx.tracer.service.CostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 预算告警钩子：每次调用结束后判定当月 Token 成本是否越过预算阈值。
 *
 * <p>注册于 ReactAgent（hooks），与执行链路解耦；判定失败不影响主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BudgetAlertHook implements AgentHook {

    private final CostService costService;

    @Override
    public HookEvent onEvent(HookEvent event) {
        if (event instanceof AfterCallEvent afterCall) {
            try {
                costService.checkBudget(afterCall.getRuntimeContext().getConversationId(),
                        afterCall.getRuntimeContext().getUserId());
            } catch (Exception e) {
                log.warn("[agentx-console] BudgetAlertHook 执行失败（忽略）: {}", e.getMessage());
            }
        }
        return event;
    }

    @Override
    public int priority() {
        // 低优先级，业务 Hook 之后执行
        return -100;
    }
}
