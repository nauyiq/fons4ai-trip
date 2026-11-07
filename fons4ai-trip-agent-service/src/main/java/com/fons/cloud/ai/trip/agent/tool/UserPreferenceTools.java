package com.fons.cloud.ai.trip.agent.tool;

import com.fons.cloud.ai.agent.core.AgentScopeRuntimeActions;
import com.fons.cloud.ai.trip.application.user.PreferenceApplicationService;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.dto.preference.PreferenceData.*;
import com.fons.cloud.common.exception.BsException;
import com.fons.cloud.common.result.R;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 页面偏好的查询和修改工具；与页面共用同一应用服务和乐观锁保存规则。
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserPreferenceTools implements BaseTool {
    public static final String QUERY = "query_user_preferences";
    public static final String UPDATE = "update_user_preferences";
    public static final List<String> TOOLS = List.of(QUERY, UPDATE);
    private final PreferenceApplicationService preferences;

    @Tool(name = QUERY, description = "查询当前用户偏好页面中保存的差旅偏好、版本和选项目录。"
            + "交通和酒店推荐、行程规划使用长期默认前先查询；历史记忆冲突时以这里的设置为准，用户本次明确要求优先。")
    public R<Snapshot> query(RuntimeContext context) {
        try {
            return R.success(preferences.query(context.getUserId()));
        } catch (BsException error) {
            return R.failed(error.getExceptionCode().getCode(), error.getMessage());
        } catch (Exception error) {
            log.error("偏好查询工具失败", error);
            return R.failed(TripAgentResultCode.PREFERENCE_READ_FAILED);
        }
    }

    @Tool(name = UPDATE, description = "用户明确要求设置、修改或清除长期差旅偏好时，更新偏好页面对应的同一份数据。"
            + "先查询当前version和目录，只提交本次修改；临时行程要求不自动保存为长期偏好。"
            + "版本冲突后重新查询并确认本次修改，只有工具成功才表示页面设置已更新。")
    public R<Snapshot> update(RuntimeContext context,
                                @ToolParam(name = "expectedVersion", description = "query_user_preferences返回的整份偏好版本字符串。") String version,
                                @ToolParam(name = "changes", description = "本次修改列表，每项为key、operation(SET或CLEAR)、value。"
                                        + "SET使用目录选项编码，CLEAR不带value，不得重复key。") List<Change> changes) {
        try {
            AgentScopeRuntimeActions actions = context.get(AgentScopeRuntimeActions.class);
            if (actions == null) {
                return R.failed(TripAgentResultCode.PREFERENCE_WRITE_FAILED);
            }
            actions.requireExecutionAllowed();
            // 与页面调用同一 CRUD；工具额外保留实际写入前的既有取消检查。
            return R.success(preferences.save(context.getUserId(), new SaveRequest(version, changes),
                    actions::requireExecutionAllowed));
        } catch (BsException error) {
            return R.failed(error.getExceptionCode().getCode(), error.getMessage());
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (Exception error) {
            log.error("偏好保存工具失败", error);
            return R.failed(TripAgentResultCode.PREFERENCE_WRITE_FAILED);
        }
    }

    @Override
    public List<String> tools() {
        return TOOLS;
    }
}
