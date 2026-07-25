package com.zmbdp.chat.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zmbdp.chat.api.knowledge.constant.KnowledgeSyncMQConstants;
import com.zmbdp.chat.api.knowledge.domain.dto.KnowledgeSourceReqDTO;
import com.zmbdp.chat.api.knowledge.domain.dto.KnowledgeSyncMessage;
import com.zmbdp.chat.api.knowledge.domain.dto.SyncReqDTO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeDocumentVO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeSourceVO;
import com.zmbdp.chat.api.knowledge.domain.vo.SyncProgressVO;
import com.zmbdp.chat.api.knowledge.domain.vo.SyncTaskVO;
import com.zmbdp.chat.service.domain.entity.SysAiDocument;
import com.zmbdp.chat.service.domain.entity.SysAiKnowledgeSource;
import com.zmbdp.chat.service.mapper.SysAiDocumentMapper;
import com.zmbdp.chat.service.mapper.SysAiKnowledgeSourceMapper;
import com.zmbdp.chat.service.service.IKnowledgeLoaderService;
import com.zmbdp.chat.service.service.IKnowledgeService;
import com.zmbdp.chat.service.service.IVectorStoreService;
import com.zmbdp.common.core.utils.BeanCopyUtil;
import com.zmbdp.common.core.utils.FileUtil;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.domain.domain.dto.BasePageDTO;
import com.zmbdp.common.domain.domain.vo.BasePageVO;
import com.zmbdp.common.domain.exception.ServiceException;
import com.zmbdp.common.snowflake.service.SnowflakeIdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库管理服务实现类
 * <p>
 * 知识库管理（B端 CRUD），管理知识源、文档；由 admin-service 通过 Feign 调用。
 * <p>
 * <b>参数校验</b>：DTO 上使用 Jakarta Validation 注解（@NotBlank/@Min/@Max），
 * Controller 层通过 @Valid 触发校验，Service 层不重复手写 if 校验。
 * <p>
 * <b>知识同步（异步）</b>：{@link #sync(SyncReqDTO)} 仅生成 taskId + 投递 MQ 消息，
 * 同步流程由 {@code KnowledgeSyncConsumer} 异步消费后委托
 * {@link KnowledgeLoaderServiceImpl#syncKnowledge(String, boolean, String)} 执行；
 * 进度数据写入 Redis（key = {@code sync:progress:{taskId}}），前端通过
 * {@link #getSyncProgress(String)} 轮询查询。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class KnowledgeServiceImpl implements IKnowledgeService {

    /**
     * 默认分块大小
     */
    private static final int DEFAULT_CHUNK_SIZE = 500;

    /**
     * 默认分块重叠大小
     */
    private static final int DEFAULT_CHUNK_OVERLAP = 50;

    /**
     * 默认启用状态（1=启用）
     */
    private static final int DEFAULT_ENABLED = 1;

    /**
     * 文档状态：已删除（软删除标记）
     */
    private static final String DOCUMENT_STATUS_DELETED = "DELETED";

    /**
     * 日期格式化器（YYYYMMDD，与脚手架统一）
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 上传文件大小上限：50MB（与设计文档 08-API 接口设计 一致）
     */
    private static final long MAX_UPLOAD_FILE_SIZE = 50L * 1024 * 1024;

    /**
     * 允许上传的文件扩展名（小写，不含点）
     * <p>
     * 与设计文档 08-API 接口设计 一致：.md, .txt, .html, .java, .py, .xml, .json
     */
    private static final Set<String> ALLOWED_UPLOAD_EXTENSIONS = Arrays.stream(
                    new String[]{"md", "txt", "html", "java", "py", "xml", "json"})
            .collect(Collectors.toUnmodifiableSet());

    /**
     * 当前执行中的同步任务ID的 Redis key（value=taskId，TTL 24h）
     * <p>
     * 用于单任务约束：提交同步前读取此 key，若其指向的任务状态为 RUNNING 则跳过本次提交。
     * 由 {@link KnowledgeLoaderServiceImpl} 在获取全局锁后写入，确保无论从哪个入口触发
     * （API/MQ/XXL-JOB）都更新此 key。
     */
    private static final String SYNC_CURRENT_TASK_KEY = "sync:current-task";

    /**
     * 当前任务 key 的 TTL（24 小时，与进度数据 TTL 一致，避免脏 key 长期残留）
     */
    private static final long SYNC_CURRENT_TASK_TTL_HOURS = 24L;

    /**
     * 提交状态：已提交（新任务）
     */
    private static final String STATUS_RUNNING = "RUNNING";

    /**
     * 提交状态：已跳过（单任务约束）
     */
    private static final String STATUS_SKIPPED = "SKIPPED";

    /**
     * 知识源 mapper
     */
    @Autowired
    private SysAiKnowledgeSourceMapper sysAiKnowledgeSourceMapper;

    /**
     * 文档 mapper
     */
    @Autowired
    private SysAiDocumentMapper sysAiDocumentMapper;

    /**
     * 知识加载服务
     */
    @Autowired
    private IKnowledgeLoaderService knowledgeLoaderService;

    /**
     * 向量存储服务
     */
    @Autowired
    private IVectorStoreService vectorStoreService;

    /**
     * 雪花 ID 生成服务
     */
    @Autowired
    private SnowflakeIdService snowflakeIdService;

    /**
     * RabbitMQ 消息发送模板（用于异步触发知识同步）
     */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * Redis 操作模板（用于读取当前执行中的同步任务ID，做单任务约束判断）
     */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /*=============================================    前端调用    =============================================*/

    /**
     * 分页查询知识源列表
     *
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @param type     类型过滤（doc/javadoc/config/code，可选）
     * @return 知识源分页结果
     */
    @Override
    public BasePageVO<KnowledgeSourceVO> listSources(Integer pageNo, Integer pageSize, String type) {
        // 参数兜底
        if (pageNo == null || pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 20;
        }
        // 构建查询条件
        LambdaQueryWrapper<SysAiKnowledgeSource> queryWrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(type)) {
            queryWrapper.eq(SysAiKnowledgeSource::getType, type);
        }
        queryWrapper.orderByDesc(SysAiKnowledgeSource::getCreateDate);
        // 分页查询
        Page<SysAiKnowledgeSource> page = new Page<>(pageNo, pageSize);
        IPage<SysAiKnowledgeSource> result = sysAiKnowledgeSourceMapper.selectPage(page, queryWrapper);
        // 转换为 VO
        BasePageVO<KnowledgeSourceVO> vo = new BasePageVO<>();
        vo.setTotals(result.getTotal() > 0 ? (int) result.getTotal() : 0);
        vo.setTotalPages(BasePageDTO.calculateTotalPages(result.getTotal(), pageSize));
        List<KnowledgeSourceVO> list = new ArrayList<>();
        if (!CollectionUtils.isEmpty(result.getRecords())) {
            for (SysAiKnowledgeSource source : result.getRecords()) {
                list.add(entityToSourceVO(source));
            }
        }
        vo.setList(list);
        return vo;
    }

    /**
     * 获取知识源详情
     *
     * @param id 知识源ID
     * @return 知识源详情
     */
    @Override
    public KnowledgeSourceVO getSource(Long id) {
        SysAiKnowledgeSource source = sysAiKnowledgeSourceMapper.selectById(id);
        if (source == null) {
            throw new ServiceException(ResultCode.AI_KNOWLEDGE_SOURCE_NOT_FOUND);
        }
        return entityToSourceVO(source);
    }

    /**
     * 新增知识源
     * <p>
     * 执行流程：
     * 1. 校验 path 是否存在
     * 2. 校验 name 是否重复
     * 3. 构建实体插入数据库
     *
     * @param dto 知识源请求 DTO
     * @return 新建后的知识源 VO（含生成的 ID）
     */
    @Override
    public KnowledgeSourceVO createSource(KnowledgeSourceReqDTO dto) {
        // 1. 校验 path 是否存在（相对路径需先与 knowledge.base-path 拼接，与同步流程保持一致）
        String resolvedPath = knowledgeLoaderService.resolveSourcePath(dto.getPath());
        if (!FileUtil.exist(resolvedPath)) {
            throw new ServiceException("知识源路径不存在：" + dto.getPath(), ResultCode.INVALID_PARA.getCode());
        }
        // 2. 校验 name 是否重复
        LambdaQueryWrapper<SysAiKnowledgeSource> nameCheck = new LambdaQueryWrapper<>();
        nameCheck.eq(SysAiKnowledgeSource::getName, dto.getName());
        if (sysAiKnowledgeSourceMapper.selectCount(nameCheck) > 0) {
            throw new ServiceException("知识源名称已存在：" + dto.getName(), ResultCode.INVALID_PARA.getCode());
        }
        // 3. 构建实体
        SysAiKnowledgeSource source = new SysAiKnowledgeSource();
        BeanCopyUtil.copyProperties(dto, source);
        source.setId(snowflakeIdService.nextId());
        // enabled 字段转换（DTO 为 Boolean，实体为 Integer）
        source.setEnabled(dto.getEnabled() == null || dto.getEnabled() ? DEFAULT_ENABLED : 0);
        // 分块参数兜底
        source.setChunkSize(dto.getChunkSize() != null ? dto.getChunkSize() : DEFAULT_CHUNK_SIZE);
        source.setChunkOverlap(dto.getChunkOverlap() != null ? dto.getChunkOverlap() : DEFAULT_CHUNK_OVERLAP);
        long today = Long.parseLong(LocalDate.now().format(DATE_FORMATTER));
        source.setCreateDate(today);
        source.setUpdateDate(today);
        // 插入数据库
        sysAiKnowledgeSourceMapper.insert(source);
        log.info("新增知识源成功：id = {}, name = {}", source.getId(), source.getName());
        return entityToSourceVO(source);
    }

    /**
     * 更新知识源
     *
     * @param id  知识源ID
     * @param dto 知识源请求 DTO
     */
    @Override
    public void updateSource(Long id, KnowledgeSourceReqDTO dto) {
        SysAiKnowledgeSource existing = sysAiKnowledgeSourceMapper.selectById(id);
        if (existing == null) {
            throw new ServiceException(ResultCode.AI_KNOWLEDGE_SOURCE_NOT_FOUND);
        }
        // 校验 path 是否存在（相对路径需先与 knowledge.base-path 拼接，与同步流程保持一致）
        if (StringUtils.hasText(dto.getPath())) {
            String resolvedPath = knowledgeLoaderService.resolveSourcePath(dto.getPath());
            if (!FileUtil.exist(resolvedPath)) {
                throw new ServiceException("知识源路径不存在：" + dto.getPath(), ResultCode.INVALID_PARA.getCode());
            }
        }
        // 校验 name 是否重复（排除自身）
        if (StringUtils.hasText(dto.getName()) && !dto.getName().equals(existing.getName())) {
            LambdaQueryWrapper<SysAiKnowledgeSource> nameCheck = new LambdaQueryWrapper<>();
            nameCheck.eq(SysAiKnowledgeSource::getName, dto.getName())
                    .ne(SysAiKnowledgeSource::getId, id);
            if (sysAiKnowledgeSourceMapper.selectCount(nameCheck) > 0) {
                throw new ServiceException("知识源名称已存在：" + dto.getName(), ResultCode.INVALID_PARA.getCode());
            }
        }
        // 构建更新实体
        SysAiKnowledgeSource update = BeanCopyUtil.copyProperties(dto, SysAiKnowledgeSource.class);
        update.setId(id);
        if (dto.getEnabled() != null) {
            update.setEnabled(dto.getEnabled() ? DEFAULT_ENABLED : 0);
        }
        update.setUpdateDate(Long.parseLong(LocalDate.now().format(DATE_FORMATTER)));
        sysAiKnowledgeSourceMapper.updateById(update);
        log.info("更新知识源成功：id = {}", id);
    }

    /**
     * 删除知识源
     * <p>
     * 级联删除：
     * 1. 查询知识源下所有文档
     * 2. 遍历文档：Milvus 物理删除 + MySQL 软删除
     * 3. 知识源置为 enabled=0（保留记录，便于审计）
     *
     * @param id 知识源ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSource(Long id) {
        SysAiKnowledgeSource source = sysAiKnowledgeSourceMapper.selectById(id);
        if (source == null) {
            throw new ServiceException(ResultCode.AI_KNOWLEDGE_SOURCE_NOT_FOUND);
        }
        // 1. 查询知识源下所有文档
        LambdaQueryWrapper<SysAiDocument> docQuery = new LambdaQueryWrapper<>();
        docQuery.eq(SysAiDocument::getKnowledgeSourceId, id)
                .ne(SysAiDocument::getStatus, DOCUMENT_STATUS_DELETED);
        List<SysAiDocument> documents = sysAiDocumentMapper.selectList(docQuery);
        // 2. 遍历文档：Milvus 物理删除 + MySQL 软删除
        if (!CollectionUtils.isEmpty(documents)) {
            for (SysAiDocument doc : documents) {
                vectorStoreService.deleteByDocumentId(doc.getId());
                // MySQL 软删除（status=DELETED）
                LambdaUpdateWrapper<SysAiDocument> docUpdate = new LambdaUpdateWrapper<>();
                docUpdate.eq(SysAiDocument::getId, doc.getId())
                        .set(SysAiDocument::getStatus, DOCUMENT_STATUS_DELETED);
                sysAiDocumentMapper.update(null, docUpdate);
            }
        }
        // 3. 知识源置为 enabled=0
        LambdaUpdateWrapper<SysAiKnowledgeSource> sourceUpdate = new LambdaUpdateWrapper<>();
        sourceUpdate.eq(SysAiKnowledgeSource::getId, id)
                .set(SysAiKnowledgeSource::getEnabled, 0)
                .set(SysAiKnowledgeSource::getUpdateDate, Long.parseLong(LocalDate.now().format(DATE_FORMATTER)));
        sysAiKnowledgeSourceMapper.update(null, sourceUpdate);
        log.info("删除知识源成功：id = {}, 级联删除文档数 = {}", id, documents.size());
    }

    /**
     * 分页查询文档列表
     *
     * @param pageNo   页码
     * @param pageSize 每页数量
     * @param sourceId 知识源ID过滤（可选）
     * @param status   状态过滤（ACTIVE/DELETED/SYNCING，可选）
     * @return 文档分页结果
     */
    @Override
    public BasePageVO<KnowledgeDocumentVO> listDocuments(Integer pageNo, Integer pageSize, Long sourceId, String status) {
        // 参数兜底
        if (pageNo == null || pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 20;
        }
        // 构建查询条件
        LambdaQueryWrapper<SysAiDocument> queryWrapper = new LambdaQueryWrapper<>();
        if (sourceId != null) {
            queryWrapper.eq(SysAiDocument::getKnowledgeSourceId, sourceId);
        }
        if (StringUtils.hasText(status)) {
            queryWrapper.eq(SysAiDocument::getStatus, status);
        }
        queryWrapper.orderByDesc(SysAiDocument::getCreateDate);
        // 分页查询（列表不返回 content 字段，减小响应体）
        Page<SysAiDocument> page = new Page<>(pageNo, pageSize);
        IPage<SysAiDocument> result = sysAiDocumentMapper.selectPage(page, queryWrapper);
        // 转换为 VO
        BasePageVO<KnowledgeDocumentVO> vo = new BasePageVO<>();
        vo.setTotals(result.getTotal() > 0 ? (int) result.getTotal() : 0);
        vo.setTotalPages(BasePageDTO.calculateTotalPages(result.getTotal(), pageSize));
        List<KnowledgeDocumentVO> list = new ArrayList<>();
        if (!CollectionUtils.isEmpty(result.getRecords())) {
            for (SysAiDocument doc : result.getRecords()) {
                list.add(entityToDocumentVO(doc, false));
            }
        }
        vo.setList(list);
        return vo;
    }

    /**
     * 获取文档详情（含完整内容）
     *
     * @param id 文档ID
     * @return 文档详情
     */
    @Override
    public KnowledgeDocumentVO getDocument(Long id) {
        SysAiDocument doc = sysAiDocumentMapper.selectById(id);
        if (doc == null) {
            throw new ServiceException(ResultCode.AI_DOCUMENT_NOT_FOUND);
        }
        return entityToDocumentVO(doc, true);
    }

    /**
     * 删除文档
     * <p>
     * Milvus 物理删除 + MySQL 软删除。
     *
     * @param id 文档ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long id) {
        SysAiDocument doc = sysAiDocumentMapper.selectById(id);
        if (doc == null) {
            throw new ServiceException(ResultCode.AI_DOCUMENT_NOT_FOUND);
        }
        // 1. Milvus 物理删除
        vectorStoreService.deleteByDocumentId(id);
        // 2. MySQL 软删除
        LambdaUpdateWrapper<SysAiDocument> update = new LambdaUpdateWrapper<>();
        update.eq(SysAiDocument::getId, id)
                .set(SysAiDocument::getStatus, DOCUMENT_STATUS_DELETED)
                .set(SysAiDocument::getUpdateDate, Long.parseLong(LocalDate.now().format(DATE_FORMATTER)));
        sysAiDocumentMapper.update(null, update);
        log.info("删除文档成功：id = {}", id);
    }

    /**
     * 触发知识同步（异步）
     * <p>
     * 执行流程：
     * <ol>
     *     <li>读取 Redis key {@code sync:current-task}，若存在则查询其指向任务的进度</li>
     *     <li>若该任务状态为 RUNNING，说明已有同步任务在执行，返回 status=SKIPPED 及当前 taskId</li>
     *     <li>否则生成新 taskId（UUID），封装 {@link KnowledgeSyncMessage} 投递到 MQ</li>
     *     <li>返回 status=RUNNING 及新 taskId，前端用此 taskId 轮询进度</li>
     * </ol>
     * <p>
     * <b>单任务约束说明</b>：本方法只做"提交前去重"（best-effort），真正的并发安全由
     * {@link KnowledgeLoaderServiceImpl#syncKnowledge} 内部的 Redisson 全局锁保证。
     * 极端场景下（提交后、消费前的短暂窗口）可能投递多条 MQ 消息，后到的会在消费端因获取全局锁失败而写 SKIPPED 进度。
     *
     * @param dto 同步请求（含 sourceType、force 参数）
     * @return 同步任务提交结果（taskId + status + message）
     */
    @Override
    public SyncTaskVO sync(SyncReqDTO dto) {
        String sourceType = dto != null ? dto.getSourceType() : null;
        boolean force = dto != null && Boolean.TRUE.equals(dto.getForce());
        log.info("触发知识同步（异步）：sourceType = {}, force = {}", sourceType, force);

        // 1. 单任务约束：检查当前是否有 RUNNING 状态的同步任务
        String currentTaskId = stringRedisTemplate.opsForValue().get(SYNC_CURRENT_TASK_KEY);
        if (StringUtils.hasText(currentTaskId)) {
            SyncProgressVO currentProgress = knowledgeLoaderService.getSyncProgress(currentTaskId);
            if (currentProgress != null && STATUS_RUNNING.equals(currentProgress.getStatus())) {
                log.info("已有同步任务在执行，本次提交被跳过：currentTaskId = {}", currentTaskId);
                SyncTaskVO vo = new SyncTaskVO();
                vo.setTaskId(currentTaskId);
                vo.setStatus(STATUS_SKIPPED);
                vo.setMessage("已有同步任务在执行，可使用返回的 taskId 查询进度");
                return vo;
            }
        }

        // 2. 生成新 taskId 并投递 MQ 消息
        String taskId = UUID.randomUUID().toString().replace("-", "");
        try {
            KnowledgeSyncMessage message = new KnowledgeSyncMessage();
            message.setTaskId(taskId);
            message.setSourceType(sourceType);
            message.setForce(force);
            rabbitTemplate.convertAndSend(
                    KnowledgeSyncMQConstants.EXCHANGE,
                    KnowledgeSyncMQConstants.ROUTING_KEY,
                    message);
            log.info("知识同步 MQ 消息已投递：taskId = {}, sourceType = {}, force = {}", taskId, sourceType, force);
        } catch (Exception e) {
            log.error("发送知识同步 MQ 消息失败：sourceType = {}, force = {}", sourceType, force, e);
            throw new ServiceException("触发知识同步失败：" + e.getMessage());
        }

        // 3. 返回提交结果
        SyncTaskVO vo = new SyncTaskVO();
        vo.setTaskId(taskId);
        vo.setStatus(STATUS_RUNNING);
        vo.setMessage("同步任务已提交，请使用 taskId 轮询进度");
        return vo;
    }

    /**
     * 查询同步任务进度
     * <p>
     * 委托给 {@link IKnowledgeLoaderService#getSyncProgress} 从 Redis 读取进度数据。
     *
     * @param taskId 同步任务ID
     * @return 进度数据；taskId 不存在或已过期返回 null
     */
    @Override
    public SyncProgressVO getSyncProgress(String taskId) {
        return knowledgeLoaderService.getSyncProgress(taskId);
    }

    /**
     * 上传文档到指定知识源
     * <p>
     * 校验文件非空、文件大小 ≤ 50MB、文件扩展名合法后，读取文件内容委托给
     * {@link IKnowledgeLoaderService#uploadDocument} 执行保存+分块+向量化。
     *
     * @param knowledgeSourceId 知识源ID
     * @param file              上传的文件（MultipartFile）
     * @return 新插入的文档 VO（含生成的 ID）
     */
    @Override
    public KnowledgeDocumentVO uploadDocument(Long knowledgeSourceId, MultipartFile file) {
        // 1. 校验 knowledgeSourceId
        if (knowledgeSourceId == null) {
            throw new ServiceException("知识源ID不能为空", ResultCode.INVALID_PARA.getCode());
        }
        // 2. 校验文件非空
        if (file == null || file.isEmpty()) {
            throw new ServiceException("上传文件不能为空", ResultCode.INVALID_PARA.getCode());
        }
        // 3. 校验文件大小
        if (file.getSize() > MAX_UPLOAD_FILE_SIZE) {
            throw new ServiceException("文件大小超过 50MB 限制：当前 " + (file.getSize() / 1024 / 1024) + "MB",
                    ResultCode.INVALID_PARA.getCode());
        }
        // 4. 校验文件名和扩展名
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            throw new ServiceException("文件名不能为空", ResultCode.INVALID_PARA.getCode());
        }
        String extension = extractExtension(originalFilename);
        if (!ALLOWED_UPLOAD_EXTENSIONS.contains(extension)) {
            throw new ServiceException("不支持的文件类型：." + extension
                    + "，仅支持 " + ALLOWED_UPLOAD_EXTENSIONS, ResultCode.INVALID_PARA.getCode());
        }
        // 5. 读取文件内容为 UTF-8 字符串
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取上传文件内容失败：fileName = {}", originalFilename, e);
            throw new ServiceException("读取文件内容失败：" + e.getMessage(), ResultCode.INVALID_PARA.getCode());
        }
        // 6. 委托给 KnowledgeLoaderService 执行保存+分块+向量化
        log.info("上传文档：knowledgeSourceId = {}, fileName = {}, size = {} 字节",
                knowledgeSourceId, originalFilename, file.getSize());
        SysAiDocument document = knowledgeLoaderService.uploadDocument(knowledgeSourceId, originalFilename, content);
        // 7. 转换为 VO 返回（含完整内容）
        return entityToDocumentVO(document, true);
    }

    /**
     * 提取文件扩展名（小写，不含点）
     *
     * @param fileName 文件名
     * @return 扩展名；无扩展名返回空字符串
     */
    private String extractExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIdx + 1).toLowerCase();
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 知识源实体转 VO
     *
     * @param source 知识源实体
     * @return 知识源 VO
     */
    private KnowledgeSourceVO entityToSourceVO(SysAiKnowledgeSource source) {
        KnowledgeSourceVO vo = new KnowledgeSourceVO();
        BeanCopyUtil.copyProperties(source, vo);
        // enabled 字段转换（实体 Integer → VO Boolean）
        vo.setEnabled(source.getEnabled() != null && source.getEnabled() == DEFAULT_ENABLED);
        return vo;
    }

    /**
     * 文档实体转 VO
     *
     * @param doc           文档实体
     * @param includeContent 是否包含完整内容（详情接口 true，列表接口 false）
     * @return 文档 VO
     */
    private KnowledgeDocumentVO entityToDocumentVO(SysAiDocument doc, boolean includeContent) {
        KnowledgeDocumentVO vo = new KnowledgeDocumentVO();
        BeanCopyUtil.copyProperties(doc, vo);
        if (!includeContent) {
            vo.setContent(null);
        }
        return vo;
    }
}