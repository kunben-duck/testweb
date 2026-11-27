package com.huawei.it.ex.one.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** Transaction-scoped workbench operations. Global task IDs are tenant-checked before child access. */
@Mapper
public interface WorkbenchMapper {
    /**
     * 以调用方请求号竞争创建任务，保留首次回执。
     * @param args taskId、tenant、owner、caller、requestId、hash、ack、agent、status、app、l1、l2、l3、title、json
     */
    int insertTask(Map<String, Object> args);

    /**
     * 读取建单指纹和首次回执，识别重发。
     * @param args tenant、caller、requestId
     */
    Map<String, Object> findCreation(Map<String, Object> args);

    /**
     * 锁定租户内的有效任务，串行裁决同时到达的写入。
     * @param args tenant、taskId
     */
    String lockTask(Map<String, Object> args);

    /**
     * 读取一致快照并保护后续子表读取。
     * @param args tenant、taskId
     */
    String shareTask(Map<String, Object> args);

    /**
     * 按旧版本条件更新整单快照。
     * @param args agent、assignment、version、status、title、json、taskId、previous
     */
    int updateTask(Map<String, Object> args);

    /**
     * 只允许已完成或已终止的任务逻辑删除。
     * @param args taskId
     */
    int deleteTask(Map<String, Object> args);

    /**
     * 增加当前版本的参与人。
     * @param args taskId、user、name
     */
    int insertMember(Map<String, Object> args);

    /**
     * 在同一事务内替换旧参与人集合。
     * @param args taskId
     */
    int clearMembers(Map<String, Object> args);

    /**
     * 确认调用用户仍在任务成员列表。
     * @param args taskId、user
     */
    int member(Map<String, Object> args);

    /**
     * 按责任人或参与人授权分页筛选任务。
     * @param args field、schemaVersion、tenant、user、app、text、number、time、bool、searchText、status、l1、l2、l3、limit、offset
     */
    List<String> listTasks(Map<String, Object> args);

    /**
     * 追加一个已提交的业务版本和来源事件回执。
     * @param args taskId、version、caller、eventId、hash、changes、ack
     */
    int insertEvent(Map<String, Object> args);

    /**
     * 核对已接受的来源事件与内容指纹。
     * @param args taskId、caller、eventId
     */
    Map<String, Object> findEvent(Map<String, Object> args);

    /**
     * 从上一个业务版本按序恢复增量。
     * @param args taskId、cursor、limit
     */
    List<Map<String, Object>> events(Map<String, Object> args);

    /**
     * 创建、移动或逻辑移除一个步骤分组。
     * @param args taskId、id、sort、removed、json
     */
    int saveGroup(Map<String, Object> args);

    /**
     * 获取本任务指定分组，包括已移除标记。
     * @param args taskId、id
     */
    String findGroup(Map<String, Object> args);

    /**
     * 按展示顺序读取当前分组。
     * @param args taskId
     */
    List<String> listGroups(Map<String, Object> args);

    /**
     * 保存动态计划中的步骤、状态和摘要。
     * @param args taskId、id、sort、removed、json、group、status、version
     */
    int saveStep(Map<String, Object> args);

    /**
     * 读取本任务的步骤以校验归属和状态。
     * @param args taskId、id
     */
    String findStep(Map<String, Object> args);

    /**
     * 按计划顺序读取未移除步骤。
     * @param args taskId
     */
    List<String> listSteps(Map<String, Object> args);

    /**
     * 判断分组是否还有未移走的步骤。
     * @param args taskId、id
     */
    int groupInUse(Map<String, Object> args);

    /**
     * 保存操作者和动作，不复制正文或凭据。
     * @param args id、taskId、actor、type、object、action、outcome
     */
    int audit(Map<String, Object> args);

    /**
     * 分页读取该任务的业务操作日志。
     * @param args taskId、limit、offset
     */
    List<Map<String, Object>> logs(Map<String, Object> args);

    /**
     * 关联主会话或当前参与人的私聊。
     * @param args taskId、user、session、type
     */
    int insertSession(Map<String, Object> args);

    /**
     * 查找当前用户的任务会话。
     * @param args taskId、user
     */
    String session(Map<String, Object> args);

    /**
     * 根据归属与会话确定可信路由。
     * @param args session、user、tenant
     */
    String sessionTask(Map<String, Object> args);

    /**
     * 解除已删除个人会话的关联。
     * @param args taskId、user
     */
    int dropSession(Map<String, Object> args);

    /**
     * 保存权威人工确认项和允许回答的成员。
     * @param args taskId、id、agent、assignment、external、step、operation、json
     */
    int insertInteraction(Map<String, Object> args);

    /**
     * 在处理答案时核对原始问卷及权限。
     * @param args taskId、id
     */
    Map<String, Object> interaction(Map<String, Object> args);

    /**
     * 获取待办与历史答案供权限投影。
     * @param args taskId
     */
    List<Map<String, Object>> interactions(Map<String, Object> args);

    /**
     * 在当前指派和等待状态下原子接受答案。
     * @param args json、user、taskId、id、assignment
     */
    int answer(Map<String, Object> args);

    /**
     * 关闭终态或交接时尚未回答的确认项。
     * @param args taskId
     */
    int cancelWaits(Map<String, Object> args);

    /**
     * 持久化待投递的用户操作。
     * @param args id、taskId、user、agent、assignment、key、hash、type、json
     */
    int insertCommand(Map<String, Object> args);

    /**
     * 识别重复命令并返回原命令编号。
     * @param args taskId、user、key
     */
    Map<String, Object> priorCommand(Map<String, Object> args);

    /**
     * 有界扫描新命令和租约过期命令。
     * @param args 无
     */
    List<String> dueCommands(Map<String, Object> args);

    /**
     * 使用租约令牌防止并发重复投递。
     * @param args lease、until、id
     */
    int claimCommand(Map<String, Object> args);

    /**
     * 读取命令与当前任务指派，防止旧指派推进。
     * @param args id
     */
    Map<String, Object> command(Map<String, Object> args);

    /**
     * 按租约令牌提交投递结果或退避时间。
     * @param args status、next、id、lease
     */
    int releaseCommand(Map<String, Object> args);

    /**
     * 为一个执行范围首次建立输出游标。
     * @param args taskId、scope
     */
    int ensureStream(Map<String, Object> args);

    /**
     * 锁读已接受的最大输出序号。
     * @param args taskId、scope
     */
    Long lockStream(Map<String, Object> args);

    /**
     * 读取详情对应的可恢复输出位置。
     * @param args taskId、scope
     */
    Long streamSeq(Map<String, Object> args);

    /**
     * 在批次事务中推进输出水位。
     * @param args seq、taskId、scope
     */
    int advanceStream(Map<String, Object> args);

    /**
     * 读取单次操作的解析检查点与输入水位。
     * @param args taskId、operation
     */
    Map<String, Object> operation(Map<String, Object> args);

    /**
     * 同步保存本次操作正文和跨批解析状态。
     * @param args taskId、scope、operation、agent、assignment、last、status、content、checkpoint
     */
    int saveOperation(Map<String, Object> args);

    /**
     * 读取一个范围中各次操作的正文。
     * @param args taskId、scope
     */
    List<Map<String, Object>> operations(Map<String, Object> args);

    /**
     * 保存新展示分段或续写未闭合的卡片分段。
     * @param args taskId、operation、ord、json
     */
    int savePart(Map<String, Object> args);

    /**
     * 批量读取一个范围的分段，不执行逐操作查询。
     * @param args taskId、scope
     */
    List<Map<String, Object>> parts(Map<String, Object> args);

    /**
     * 保存输入批次指纹和对应已归一化的回放。
     * @param args taskId、scope、operation、first、last、hash、startSeq、seq、json、expiry
     */
    int insertFrame(Map<String, Object> args);

    /**
     * 核对旧批次指纹并获取原始确认水位。
     * @param args taskId、operation、first
     */
    Map<String, Object> frame(Map<String, Object> args);

    /**
     * 按游标恢复仍在保留期的执行输出。
     * @param args taskId、scope、cursor、limit
     */
    List<String> frames(Map<String, Object> args);

    /**
     * 每次只删除少量过期回放，不删除操作正文。
     * @param args 无
     */
    int pruneFrames(Map<String, Object> args);

    /**
     * 获取该产品类别指定或最新发布的扩展字典。
     * @param args tenant、app、category、version
     */
    Map<String, Object> schema(Map<String, Object> args);

    /**
     * 保存已通过版本化字典校验的类型化值。
     * @param args taskId、version、key、text、number、time、bool
     */
    int indexValue(Map<String, Object> args);

    /**
     * 在事务中替换旧的扩展字段投影。
     * @param args taskId
     */
    int clearValues(Map<String, Object> args);

    /** 锁定可信来源会话，防止删除与对话建单关联交错。
     * @param args tenant、user、session
     */
    String lockSourceSession(Map<String, Object> args);

    /** 删除已删除会话的个人任务关联，不操作任务状态。
     * @param args tenant、user、session
     */
    int unlinkDeleted(Map<String, Object> args);

    /** 首帧到达时预留操作编号，禁止并行范围意外复用同一编号。
     * @param args taskId、scope、operation、agent、assignment
     */
    int reserveOperation(Map<String, Object> args);

    /** 汇总当前计划总步骤与已完成步骤；跳过保留在分母。
     * @param args taskId
     */
    Map<String, Object> stepCounts(Map<String, Object> args);
}
