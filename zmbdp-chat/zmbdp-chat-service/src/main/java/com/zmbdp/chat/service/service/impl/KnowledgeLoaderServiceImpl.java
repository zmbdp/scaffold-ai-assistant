package com.zmbdp.chat.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
import com.zmbdp.chat.service.domain.entity.SysAiDocument;
import com.zmbdp.chat.service.domain.entity.SysAiKnowledgeSource;
import com.zmbdp.chat.service.mapper.SysAiDocumentMapper;
import com.zmbdp.chat.service.mapper.SysAiKnowledgeSourceMapper;
import com.zmbdp.chat.service.service.IKnowledgeLoaderService;
import com.zmbdp.chat.service.service.IModelService;
import com.zmbdp.chat.service.service.IVectorStoreService;
import com.zmbdp.common.core.utils.FileUtil;
import com.zmbdp.common.domain.domain.ResultCode;
import com.zmbdp.common.domain.exception.ServiceException;
import com.zmbdp.common.redis.service.RedissonLockService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识加载服务实现类
 * <p>
 * 负责知识加载、分块处理；执行知识同步（增量/全量）。
 * <p>
 * <b>核心流程</b>（{@link #syncKnowledge(boolean)} 12 步）：
 * <ol>
 *     <li>从 sys_ai_knowledge_source 表查询所有 enabled=1 的知识源</li>
 *     <li>遍历每个知识源，按知识源粒度加分布式锁（{@code knowledge:sync:{knowledgeSourceId}}）</li>
 *     <li>遍历知识源目录，收集当前所有文件路径</li>
 *     <li>从 sys_ai_document 表查询该知识源的已有文档列表</li>
 *     <li>对比文件路径，识别新增/更新/删除三种状态</li>
 *     <li>处理新增文件：读内容→算哈希→分块→插 MySQL→写 Milvus</li>
 *     <li>处理更新文件：读内容→算哈希→更新 MySQL(version+1)→删旧 Milvus 分块→分块→写新 Milvus 分块</li>
 *     <li>处理删除文件：删 Milvus 分块→更新 MySQL 状态为 DELETED（软删除）</li>
 *     <li>force=true 时跳过哈希检查，所有文件视为更新</li>
 *     <li>更新 MySQL 中 chunk_count 字段</li>
 *     <li>释放分布式锁</li>
 *     <li>记录操作日志（通过 @LogAction，本类不直接处理）</li>
 * </ol>
 * <p>
 * <b>分块算法</b>（4 种类型）：
 * <ul>
 *     <li>Markdown：按 {@code #}/{@code ##} 章节分块，超长按段落二次切分</li>
 *     <li>Java 源码：按 public/private/protected 方法签名分块（简化版，未用 AST）</li>
 *     <li>JavaDoc HTML：按 class/method 标签分块（简化版）</li>
 *     <li>配置文件：按顶层配置节切分（YAML 顶层 key）</li>
 * </ul>
 * <p>
 * <b>文件操作</b>：复用 {@link FileUtil}（继承 Hutool FileUtil，禁止直接使用 Java NIO）。
 *
 * @author 稚名不带撇
 */
@Slf4j
@Service
public class KnowledgeLoaderServiceImpl implements IKnowledgeLoaderService {

    /**
     * 分布式锁 key 前缀（按知识源粒度加锁）
     */
    private static final String LOCK_PREFIX = "knowledge:sync:";

    /**
     * 分布式锁租约时间（30 分钟，防止死锁）
     */
    private static final long LOCK_LEASE_SECONDS = 1800L;

    /**
     * 文档状态：活跃
     */
    private static final String STATUS_ACTIVE = "ACTIVE";

    /**
     * 文档状态：已删除（软删除）
     */
    private static final String STATUS_DELETED = "DELETED";

    /**
     * 知识源类型：Markdown 文档
     */
    private static final String TYPE_DOC = "doc";

    /**
     * 知识源类型：JavaDoc HTML
     */
    private static final String TYPE_JAVADOC = "javadoc";

    /**
     * 知识源类型：配置文件
     */
    private static final String TYPE_CONFIG = "config";

    /**
     * 知识源类型：Java 源码
     */
    private static final String TYPE_CODE = "code";

    /**
     * 日期格式化器（YYYYMMDD，与脚手架统一）
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * metadata 中分块索引的 key（与 Milvus 字段名对齐）
     */
    private static final String META_DOCUMENT_ID = "document_id";

    /**
     * metadata 中分块索引的 key（与 Milvus 字段名对齐）
     */
    private static final String META_CHUNK_INDEX = "chunk_index";

    /**
     * metadata 中来源类型的 key
     */
    private static final String META_SOURCE_TYPE = "source_type";

    /**
     * metadata 中来源路径的 key
     */
    private static final String META_SOURCE_PATH = "source_path";

    /**
     * metadata 中所属模块的 key
     */
    private static final String META_MODULE = "module";

    /**
     * metadata 中功能类别的 key
     */
    private static final String META_CATEGORY = "category";

    /**
     * metadata 中文档标题的 key
     */
    private static final String META_TITLE = "title";

    /**
     * metadata 中创建时间戳的 key
     */
    private static final String META_CREATE_TIME = "create_time";

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
     * 向量存储服务
     */
    @Autowired
    private IVectorStoreService vectorStoreService;

    /**
     * 模型管理服务
     */
    @Autowired
    private IModelService modelService;

    /**
     * Redisson 分布式锁服务
     */
    @Autowired
    private RedissonLockService redissonLockService;

    /**
     * 知识库根路径（从 Nacos {@code knowledge.base-path} 读取）
     */
    @Value("${knowledge.base-path:}")
    private String knowledgeBasePath;

    /*=============================================    内部调用    =============================================*/

    /**
     * 加载所有知识源
     * <p>
     * 依次加载 docs/*.md 文档、JavaDoc HTML 文档、Nacos 配置和部署配置文件，
     * 同步写入 MySQL 和 Milvus。
     * <p>
     * <b>实现说明</b>：等同于 {@link #syncKnowledge(boolean)} 增量同步。
     */
    @Override
    public void loadAllKnowledge() {
        log.info("开始加载所有知识源（增量同步）");
        SyncResultVO result = syncKnowledge(false);
        log.info("加载所有知识源完成：{}", result);
    }

    /**
     * 加载 docs 目录下的 Markdown 文档
     * <p>
     * 扫描指定目录下所有 *.md 文件，读取内容并构造 Spring AI {@link Document} 列表（未分块）。
     *
     * @return Markdown 文档列表
     */
    @Override
    public List<Document> loadDocs() {
        return scanDocumentsByType(TYPE_DOC, ".md");
    }

    /**
     * 加载 JavaDoc HTML 文档
     * <p>
     * 扫描指定目录下所有 *.html 文件，读取内容并构造 Spring AI {@link Document} 列表（未分块）。
     *
     * @return JavaDoc 文档列表
     */
    @Override
    public List<Document> loadJavaDoc() {
        return scanDocumentsByType(TYPE_JAVADOC, ".html");
    }

    /**
     * 加载 Nacos 配置和部署配置文件
     * <p>
     * 扫描指定目录下所有 *.yaml/*.yml/*.properties 文件，构造 Spring AI {@link Document} 列表（未分块）。
     * <p>
     * <b>实现说明</b>：v1.0 仅扫描本地部署配置文件（{@code deploy/${env}/res/sql/DEFAULT_GROUP/}），
     * Nacos 远程配置拉取由 {@code INacosConfigService} 提供，本方法不直接调用（避免循环依赖）。
     *
     * @return 配置文件文档列表
     */
    @Override
    public List<Document> loadConfigs() {
        List<Document> docs = new ArrayList<>();
        docs.addAll(scanDocumentsByType(TYPE_CONFIG, ".yaml"));
        docs.addAll(scanDocumentsByType(TYPE_CONFIG, ".yml"));
        docs.addAll(scanDocumentsByType(TYPE_CONFIG, ".properties"));
        return docs;
    }

    /**
     * 根据文档类型分块处理
     * <p>
     * 根据文档类型（Markdown/JavaDoc/配置文件/Java 源码）选择对应的分块策略，
     * 将原文档拆分为多个 {@link Document} 分块。
     * <p>
     * <b>分块参数</b>：chunkSize 默认 500，chunkOverlap 默认 50。
     *
     * @param document 原始文档（metadata 中应含 source_type、source_path、module、category、title）
     * @return 分块后的文档列表（每个分块含 chunkIndex、document_id 暂不设置由调用方回填）
     */
    @Override
    public List<Document> chunkDocument(Document document) {
        if (document == null || document.getText() == null || document.getText().isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Object> metadata = document.getMetadata();
        String sourceType = !metadata.isEmpty() ? (String) metadata.get(META_SOURCE_TYPE) : null;
        if (sourceType == null) {
            sourceType = TYPE_DOC;
        }
        String content = document.getText();
        int chunkSize = getIntMetadata(metadata, "chunk_size", 500);
        int chunkOverlap = getIntMetadata(metadata, "chunk_overlap", 50);

        List<String> chunks = switch (sourceType) {
            case TYPE_CODE -> chunkJavaCode(content, chunkSize, chunkOverlap);
            case TYPE_JAVADOC -> chunkJavaDoc(content, chunkSize, chunkOverlap);
            case TYPE_CONFIG -> chunkConfig(content, chunkSize, chunkOverlap);
            default -> chunkMarkdown(content, chunkSize, chunkOverlap);
        };

        List<Document> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            // 复制原 metadata，并补充 chunk_index
            Map<String, Object> chunkMetadata = !metadata.isEmpty() ? new HashMap<>(metadata) : new HashMap<>();
            chunkMetadata.put(META_CHUNK_INDEX, i);
            chunkMetadata.put(META_CREATE_TIME, System.currentTimeMillis());
            result.add(new Document(chunks.get(i), chunkMetadata));
        }
        log.info("文档分块完成：sourceType = {}, 原文长度 = {}, 分块数 = {}", sourceType, content.length(), result.size());
        return result;
    }

    /**
     * 执行知识同步
     * <p>
     * 执行 12 步同步流程（含 Redisson 分布式锁），支持增量/全量同步。
     *
     * @param force 是否强制全量同步（true=全量跳过哈希检查，false=增量）
     * @return 同步结果统计
     */
    @Override
    public SyncResultVO syncKnowledge(boolean force) {
        long startTime = System.currentTimeMillis();
        SyncResultVO result = new SyncResultVO();
        result.setTotalDocuments(0);
        result.setUpdatedDocuments(0);
        result.setDeletedDocuments(0);
        result.setSkippedDocuments(0);
        result.setFailedDocuments(0);

        // 1. 查询所有 enabled=1 的知识源
        LambdaQueryWrapper<SysAiKnowledgeSource> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysAiKnowledgeSource::getEnabled, 1);
        List<SysAiKnowledgeSource> sources = sysAiKnowledgeSourceMapper.selectList(queryWrapper);
        if (sources == null || sources.isEmpty()) {
            log.warn("知识同步：未找到 enabled=1 的知识源，跳过");
            result.setDuration(System.currentTimeMillis() - startTime);
            return result;
        }
        log.info("知识同步开始：force = {}, 知识源数量 = {}", force, sources.size());

        for (SysAiKnowledgeSource source : sources) {
            // 2. 按知识源粒度加分布式锁
            String lockKey = LOCK_PREFIX + source.getId();
            RLock lock = redissonLockService.acquire(lockKey);
            if (lock == null) {
                log.warn("知识源 {} 同步锁获取失败，已有同步任务在执行，跳过", source.getName());
                continue;
            }
            try {
                syncSingleSource(source, force, result);
            } catch (Exception e) {
                log.error("知识源 {} 同步异常", source.getName(), e);
                result.setFailedDocuments(result.getFailedDocuments() + 1);
            } finally {
                try {
                    redissonLockService.releaseLock(lock);
                } catch (Exception e) {
                    log.warn("释放知识源 {} 同步锁异常：{}", source.getName(), e.getMessage());
                }
            }
        }

        result.setDuration(System.currentTimeMillis() - startTime);
        log.info("知识同步完成：{}", result);
        return result;
    }

    /**
     * 上传单个文档到指定知识源
     * <p>
     * 将文件内容保存到知识源 path 目录下，并立即对该文件执行分块、向量化、写入 Milvus
     * （复用 {@link #processAddedFile} 逻辑），无需等待定时同步任务。
     * <p>
     * <b>执行流程</b>：
     * <ol>
     *     <li>校验知识源存在且 enabled=1</li>
     *     <li>拼接完整文件路径：{@code resolveSourcePath(source.path) + File.separator + fileName}</li>
     *     <li>校验文件不存在（避免覆盖已有文件）</li>
     *     <li>保存文件内容到磁盘（自动创建父目录）</li>
     *     <li>调用 {@link #processAddedFile} 处理新文件（读内容→算哈希→分块→插 MySQL→写 Milvus）</li>
     *     <li>返回新插入的 {@link SysAiDocument}（含生成的 ID）</li>
     * </ol>
     * <p>
     * <b>失败回滚</b>：若 Embedding 或 Milvus 写入失败，{@link #processAddedFile} 内部会删除
     * sys_ai_document 记录，但磁盘文件保留（便于用户排查后重新上传或下次同步重试）。
     *
     * @param knowledgeSourceId 知识源ID
     * @param fileName          文件名（含扩展名，如 ADD_NEW_MODULE.md）
     * @param content           文件文本内容
     * @return 新插入的文档记录（含生成的 ID、version=1、status=ACTIVE）
     * @throws com.zmbdp.common.domain.exception.ServiceException 知识源不存在或未启用、文件已存在
     */
    @Override
    public SysAiDocument uploadDocument(Long knowledgeSourceId, String fileName, String content) {
        // 1. 校验知识源存在
        if (knowledgeSourceId == null) {
            throw new ServiceException("知识源ID不能为空", ResultCode.INVALID_PARA.getCode());
        }
        if (!StringUtils.hasText(fileName)) {
            throw new ServiceException("文件名不能为空", ResultCode.INVALID_PARA.getCode());
        }
        SysAiKnowledgeSource source = sysAiKnowledgeSourceMapper.selectById(knowledgeSourceId);
        if (source == null) {
            throw new ServiceException(ResultCode.AI_KNOWLEDGE_SOURCE_NOT_FOUND);
        }
        if (source.getEnabled() == null || source.getEnabled() != 1) {
            throw new ServiceException("知识源未启用，无法上传文档：sourceId = " + knowledgeSourceId,
                    ResultCode.INVALID_PARA.getCode());
        }
        // 2. 拼接完整文件路径（复用 resolveSourcePath 处理相对/绝对路径）
        String sourcePath = resolveSourcePath(source.getPath());
        // 规范化文件名，防止路径穿越（如 ../etc/passwd）
        String safeFileName = fileName.replace('\\', '/');
        if (safeFileName.contains("/")) {
            // 取最后一段作为文件名，防止用户传入相对路径
            safeFileName = safeFileName.substring(safeFileName.lastIndexOf('/') + 1);
        }
        if (!StringUtils.hasText(safeFileName)) {
            throw new ServiceException("文件名不合法：", ResultCode.INVALID_PARA.getCode());
        }
        String filePath = sourcePath + File.separator + safeFileName;
        // 3. 校验文件不存在（避免覆盖已有文件）
        if (FileUtil.exist(filePath)) {
            throw new ServiceException("文件已存在，请删除后重新上传或修改文件名：" + safeFileName,
                    ResultCode.INVALID_PARA.getCode());
        }
        // 4. 保存文件到磁盘（touch 自动创建父目录，writeUtf8String 写入内容）
        try {
            FileUtil.touch(filePath);
            FileUtil.writeUtf8String(content, filePath);
            log.info("文件已保存到磁盘：path = {}, size = {} 字符", filePath, content.length());
        } catch (Exception e) {
            log.error("保存文件到磁盘失败：path = {}", filePath, e);
            throw new ServiceException("保存文件失败：" + e.getMessage(), ResultCode.INVALID_PARA.getCode());
        }
        // 5. 调用 processAddedFile 处理新文件（读内容→算哈希→分块→插 MySQL→写 Milvus）
        File file = new File(filePath);
        try {
            SysAiDocument document = processAddedFile(source, file);
            log.info("文档上传处理完成：knowledgeSourceId = {}, fileName = {}, documentId = {}",
                    knowledgeSourceId, safeFileName, document.getId());
            return document;
        } catch (Exception e) {
            // processAddedFile 内部已回滚 sys_ai_document 记录；磁盘文件保留，便于下次同步重试
            log.error("文档上传处理失败（磁盘文件已保留，下次同步会重试）：path = {}", filePath, e);
            throw new ServiceException("文档向量化失败：" + e.getMessage(), ResultCode.INVALID_PARA.getCode());
        }
    }

    /*=============================================    私有方法    =============================================*/

    /**
     * 同步单个知识源
     *
     * @param source 知识源
     * @param force  是否强制全量
     * @param result 同步结果（累加）
     */
    private void syncSingleSource(SysAiKnowledgeSource source, boolean force, SyncResultVO result) {
        String sourcePath = resolveSourcePath(source.getPath());
        if (!FileUtil.exist(sourcePath)) {
            log.warn("知识源 {} 路径不存在：{}", source.getName(), sourcePath);
            return;
        }

        // 3. 遍历知识源目录，收集当前所有文件路径
        List<File> currentFiles = listFiles(sourcePath, source.getType());
        Map<String, File> currentFileMap = new HashMap<>();
        for (File file : currentFiles) {
            currentFileMap.put(file.getAbsolutePath(), file);
        }

        // 4. 从 MySQL 查询该知识源的已有文档列表（不含 DELETED）
        LambdaQueryWrapper<SysAiDocument> docQuery = new LambdaQueryWrapper<>();
        docQuery.eq(SysAiDocument::getKnowledgeSourceId, source.getId())
                .ne(SysAiDocument::getStatus, STATUS_DELETED);
        List<SysAiDocument> existingDocs = sysAiDocumentMapper.selectList(docQuery);
        Map<String, SysAiDocument> existingDocMap = existingDocs.stream()
                .collect(Collectors.toMap(SysAiDocument::getPath, d -> d, (a, b) -> a));

        // 5. 识别三种状态
        List<File> addedFiles = new ArrayList<>();
        List<File> updatedFiles = new ArrayList<>();
        List<SysAiDocument> deletedDocs = new ArrayList<>();

        for (Map.Entry<String, File> entry : currentFileMap.entrySet()) {
            if (!existingDocMap.containsKey(entry.getKey())) {
                addedFiles.add(entry.getValue());
            } else {
                SysAiDocument existing = existingDocMap.get(entry.getKey());
                if (force) {
                    updatedFiles.add(entry.getValue());
                } else {
                    // 增量模式：必须同时满足三个条件才跳过，否则重新同步避免数据不一致
                    // 1. hash 相同（文件内容未变）
                    // 2. chunk_count > 0（之前同步成功写入过分块；失败回滚时会置 0，必须重试）
                    // 3. hash 不为空字符串（失败回滚时会清空 hash，必须重试）
                    // 注：不直接查 Milvus 是为了性能（每个文件查一次 Milvus 太重），
                    //     chunk_count > 0 是 DB 侧的成功标志，已能覆盖 99% 的失败场景
                    String currentHash = calculateSha256(FileUtil.readUtf8String(entry.getValue()));
                    boolean hashMatch = currentHash.equals(existing.getHash());
                    boolean hasChunks = existing.getChunkCount() != null && existing.getChunkCount() > 0;
                    boolean hashValid = existing.getHash() != null && !existing.getHash().isEmpty();
                    if (hashMatch && hasChunks && hashValid) {
                        result.setSkippedDocuments(result.getSkippedDocuments() + 1);
                    } else {
                        // 走更新流程重新 Embedding + 写入 Milvus
                        // 场景：hash 不匹配（文件改了）/ chunk_count=0（之前失败）/ hash 为空（之前失败回滚）
                        updatedFiles.add(entry.getValue());
                        if (!hashMatch) {
                            log.debug("文件 hash 变更，标记为更新：{}", entry.getKey());
                        } else {
                            log.warn("文件 DB 记录异常（chunk_count = {}, hash = {}），重新同步：{}",
                                    existing.getChunkCount(), existing.getHash(), entry.getKey());
                        }
                    }
                }
            }
        }
        for (SysAiDocument existing : existingDocs) {
            if (!currentFileMap.containsKey(existing.getPath())) {
                deletedDocs.add(existing);
            }
        }

        result.setTotalDocuments(result.getTotalDocuments() + currentFiles.size());

        // 6. 处理新增文件
        for (File file : addedFiles) {
            try {
                processAddedFile(source, file);
                result.setUpdatedDocuments(result.getUpdatedDocuments() + 1);
            } catch (Exception e) {
                log.error("处理新增文件失败：{}", file.getAbsolutePath(), e);
                result.setFailedDocuments(result.getFailedDocuments() + 1);
            }
        }

        // 7. 处理更新文件
        for (File file : updatedFiles) {
            try {
                processUpdatedFile(source, file, existingDocMap.get(file.getAbsolutePath()));
                result.setUpdatedDocuments(result.getUpdatedDocuments() + 1);
            } catch (Exception e) {
                log.error("处理更新文件失败：{}", file.getAbsolutePath(), e);
                result.setFailedDocuments(result.getFailedDocuments() + 1);
            }
        }

        // 8. 处理删除文件
        for (SysAiDocument doc : deletedDocs) {
            try {
                processDeletedDocument(doc);
                result.setDeletedDocuments(result.getDeletedDocuments() + 1);
            } catch (Exception e) {
                log.error("处理删除文件失败：documentId = {}", doc.getId(), e);
                result.setFailedDocuments(result.getFailedDocuments() + 1);
            }
        }

        // 9. 更新知识源的最后同步日期
        SysAiKnowledgeSource updateSource = new SysAiKnowledgeSource();
        updateSource.setId(source.getId());
        updateSource.setLastSyncDate(Long.parseLong(LocalDate.now().format(DATE_FORMATTER)));
        updateSource.setUpdateDate(Long.parseLong(LocalDate.now().format(DATE_FORMATTER)));
        sysAiKnowledgeSourceMapper.updateById(updateSource);

        log.info("知识源 {} 同步完成：新增/更新 = {}, 删除 = {}, 跳过 = {}",
                source.getName(), addedFiles.size() + updatedFiles.size(), deletedDocs.size(),
                result.getSkippedDocuments());
    }

    /**
     * 处理新增文件
     * <p>
     * 读内容→算哈希→分块→插 MySQL→写 Milvus。
     *
     * @param source 知识源
     * @param file   文件
     * @return 新插入的 sys_ai_document 记录（含生成的 ID）
     */
    private SysAiDocument processAddedFile(SysAiKnowledgeSource source, File file) {
        String content = FileUtil.readUtf8String(file);
        String hash = calculateSha256(content);

        // 插入 sys_ai_document 表
        SysAiDocument document = new SysAiDocument();
        document.setKnowledgeSourceId(source.getId());
        document.setTitle(file.getName());
        document.setPath(file.getAbsolutePath());
        document.setContent(content);
        document.setType(source.getType());
        document.setModule(extractModuleName(source.getPath()));
        document.setCategory(source.getType());
        document.setStatus(STATUS_ACTIVE);
        document.setVersion(1);
        document.setHash(hash);
        String today = LocalDate.now().format(DATE_FORMATTER);
        document.setCreateDate(Long.parseLong(today));
        document.setUpdateDate(Long.parseLong(today));
        sysAiDocumentMapper.insert(document);

        try {
            // 分块并写入 Milvus（含 Embedding 调用，可能因网络抖动/超时失败）
            List<Document> chunks = buildChunks(source, file, content, document.getId());
            document.setChunkCount(chunks.size());
            sysAiDocumentMapper.updateById(document);

            if (!chunks.isEmpty()) {
                vectorStoreService.addDocuments(chunks);
            }
            log.info("新增文件处理完成：path = {}, chunkCount = {}", file.getAbsolutePath(), chunks.size());
            return document;
        } catch (Exception e) {
            // Embedding 或 Milvus 写入失败：回滚 sys_ai_document 记录，避免 DB 有记录但 Milvus 无向量的数据不一致
            // 下次同步时该文件会被重新识别为"新增"（因 DB 记录已删除），重新尝试 Embedding + 写入
            log.error("处理新增文件失败，回滚 sys_ai_document 记录：path = {}", file.getAbsolutePath(), e);
            try {
                sysAiDocumentMapper.deleteById(document.getId());
            } catch (Exception rollbackEx) {
                log.error("回滚 sys_ai_document 失败，需手动清理：documentId = {}", document.getId(), rollbackEx);
            }
            throw new RuntimeException("处理新增文件失败：" + file.getAbsolutePath(), e);
        }
    }

    /**
     * 处理更新文件
     * <p>
     * 读内容→算哈希→更新 MySQL(version+1)→删旧 Milvus 分块→分块→写新 Milvus 分块。
     *
     * @param source   知识源
     * @param file     文件
     * @param existing MySQL 中已有文档记录
     */
    private void processUpdatedFile(SysAiKnowledgeSource source, File file, SysAiDocument existing) {
        String content = FileUtil.readUtf8String(file);
        String hash = calculateSha256(content);

        // 删除 Milvus 旧分块（注意：此处已删旧向量，后续若 Embedding 失败，老向量也丢了，
        // 因此失败时必须把 DB 记录的 hash 清空，让下次同步重新识别为需要更新并重试）
        if (existing.getId() != null) {
            vectorStoreService.deleteByDocumentId(existing.getId());
        }

        // 更新 MySQL 文档记录
        existing.setContent(content);
        existing.setHash(hash);
        existing.setVersion(existing.getVersion() != null ? existing.getVersion() + 1 : 2);
        existing.setUpdateDate(Long.parseLong(LocalDate.now().format(DATE_FORMATTER)));
        sysAiDocumentMapper.updateById(existing);

        try {
            // 重新分块并写入 Milvus（含 Embedding 调用，可能因网络抖动/超时失败）
            List<Document> chunks = buildChunks(source, file, content, existing.getId());
            existing.setChunkCount(chunks.size());
            sysAiDocumentMapper.updateById(existing);

            if (!chunks.isEmpty()) {
                vectorStoreService.addDocuments(chunks);
            }
            log.info("更新文件处理完成：path = {}, chunkCount = {}", file.getAbsolutePath(), chunks.size());
        } catch (Exception e) {
            // Embedding 或 Milvus 写入失败：旧向量已删，新向量未写入，必须让下次同步重试
            // 清空 hash 让下次同步认为文件"已变更"重新走更新流程（否则 hash 相同会被跳过，永久无向量）
            log.error("处理更新文件失败，标记 hash 为空等待下次重试：path = {}", file.getAbsolutePath(), e);
            try {
                existing.setHash("");
                existing.setChunkCount(0);
                sysAiDocumentMapper.updateById(existing);
            } catch (Exception rollbackEx) {
                log.error("回滚 sys_ai_document 失败，需手动清理：documentId = {}", existing.getId(), rollbackEx);
            }
            throw new RuntimeException("处理更新文件失败：" + file.getAbsolutePath(), e);
        }
    }

    /**
     * 处理删除文件
     * <p>
     * 删 Milvus 分块→更新 MySQL 状态为 DELETED（软删除）。
     *
     * @param document MySQL 文档记录
     */
    private void processDeletedDocument(SysAiDocument document) {
        // 删除 Milvus 中该 document_id 的所有分块
        if (document.getId() != null) {
            vectorStoreService.deleteByDocumentId(document.getId());
        }
        // 软删除 MySQL 记录
        SysAiDocument update = new SysAiDocument();
        update.setId(document.getId());
        update.setStatus(STATUS_DELETED);
        update.setUpdateDate(Long.parseLong(LocalDate.now().format(DATE_FORMATTER)));
        sysAiDocumentMapper.updateById(update);
        log.info("删除文件处理完成：documentId = {}", document.getId());
    }

    /**
     * 构建分块列表（含 document_id 元数据）
     *
     * @param source     知识源
     * @param file       文件
     * @param content    文件内容
     * @param documentId MySQL 文档ID
     * @return 分块后的 Document 列表
     */
    private List<Document> buildChunks(SysAiKnowledgeSource source, File file, String content, Long documentId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(META_SOURCE_TYPE, source.getType());
        metadata.put(META_SOURCE_PATH, file.getAbsolutePath());
        metadata.put(META_MODULE, extractModuleName(source.getPath()));
        metadata.put(META_CATEGORY, source.getType());
        metadata.put(META_TITLE, file.getName());
        metadata.put(META_DOCUMENT_ID, documentId);
        metadata.put("chunk_size", source.getChunkSize() != null ? source.getChunkSize() : 500);
        metadata.put("chunk_overlap", source.getChunkOverlap() != null ? source.getChunkOverlap() : 50);

        Document rawDoc = new Document(content, metadata);
        return chunkDocument(rawDoc);
    }

    /**
     * 解析知识源路径（若是相对路径，拼接 knowledgeBasePath）
     *
     * @param path 配置中的路径
     * @return 绝对路径
     */
    private String resolveSourcePath(String path) {
        if (path == null || path.isEmpty()) {
            return knowledgeBasePath;
        }
        File file = new File(path);
        if (file.isAbsolute()) {
            return path;
        }
        return knowledgeBasePath + File.separator + path;
    }

    /**
     * 列出指定类型知识源目录下的所有文件
     *
     * @param sourcePath 知识源根目录
     * @param sourceType 知识源类型
     * @return 文件列表
     */
    private List<File> listFiles(String sourcePath, String sourceType) {
        File dir = new File(sourcePath);
        if (!dir.exists() || !dir.isDirectory()) {
            return new ArrayList<>();
        }
        // 按知识源类型确定扩展名
        String[] extensions = switch (sourceType) {
            case TYPE_DOC -> new String[]{"md", "markdown"};
            case TYPE_JAVADOC -> new String[]{"html", "htm"};
            case TYPE_CONFIG -> new String[]{"yaml", "yml", "properties"};
            case TYPE_CODE -> new String[]{"java"};
            default -> new String[]{"md"};
        };
        return FileUtil.loopFiles(dir, file -> {
            if (!file.isFile()) {
                return false;
            }
            String name = file.getName();
            int dotIdx = name.lastIndexOf('.');
            if (dotIdx < 0) {
                return false;
            }
            String ext = name.substring(dotIdx + 1).toLowerCase();
            for (String target : extensions) {
                if (target.equals(ext)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * 扫描指定类型的文档（用于 loadDocs/loadJavaDoc/loadConfigs）
     *
     * @param sourceType 知识源类型
     * @param extension  文件扩展名（含点，如 .md）
     * @return Document 列表（未分块）
     */
    private List<Document> scanDocumentsByType(String sourceType, String extension) {
        List<Document> docs = new ArrayList<>();
        if (knowledgeBasePath == null || knowledgeBasePath.isEmpty()) {
            log.warn("knowledge.base-path 未配置，无法加载 {} 类型文档", sourceType);
            return docs;
        }
        File baseDir = new File(knowledgeBasePath);
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            log.warn("knowledge.base-path 路径不存在或非目录：{}", knowledgeBasePath);
            return docs;
        }
        List<File> files = FileUtil.loopFiles(baseDir, file -> {
            if (!file.isFile()) {
                return false;
            }
            return file.getName().toLowerCase().endsWith(extension);
        });
        for (File file : files) {
            try {
                String content = FileUtil.readUtf8String(file);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(META_SOURCE_TYPE, sourceType);
                metadata.put(META_SOURCE_PATH, file.getAbsolutePath());
                metadata.put(META_TITLE, file.getName());
                docs.add(new Document(content, metadata));
            } catch (Exception e) {
                log.warn("读取文件失败：{}", file.getAbsolutePath(), e);
            }
        }
        log.info("扫描 {} 类型文档（{}）完成：共 {} 个", sourceType, extension, docs.size());
        return docs;
    }

    /**
     * 计算 SHA-256 哈希
     *
     * @param content 文本内容
     * @return SHA-256 十六进制字符串
     */
    private String calculateSha256(String content) {
        if (content == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("计算 SHA-256 失败，返回空字符串", e);
            return "";
        }
    }

    /**
     * 从知识源 path 中提取模块名
     * <p>
     * 例如 {@code zmbdp-common/zmbdp-common-core/src/...} 提取出 {@code common-core}。
     *
     * @param path 知识源路径
     * @return 模块名；无法提取返回 "unknown"
     */
    private String extractModuleName(String path) {
        if (path == null || path.isEmpty()) {
            return "unknown";
        }
        // 尝试匹配 zmbdp-xxx-yyy 模式
        String normalized = path.replace('\\', '/');
        int idx = normalized.indexOf("zmbdp-");
        if (idx < 0) {
            return "unknown";
        }
        String sub = normalized.substring(idx);
        // 取第一个路径段
        int slashIdx = sub.indexOf('/');
        String segment = slashIdx > 0 ? sub.substring(0, slashIdx) : sub;
        // zmbdp-common-core → common-core
        if (segment.startsWith("zmbdp-")) {
            return segment.substring("zmbdp-".length());
        }
        return segment;
    }

    /**
     * 从 metadata 中获取 int 值
     *
     * @param metadata     元数据
     * @param key          键
     * @param defaultValue 默认值
     * @return int 值
     */
    private int getIntMetadata(Map<String, Object> metadata, String key, int defaultValue) {
        if (metadata == null) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /*----------------------------- 分块算法 -----------------------------*/

    /**
     * Markdown 分块算法
     * <p>
     * 按 {@code #}/{@code ##} 章节切分；超长章节按段落（空行）二次切分；
     * 相邻分块保留 chunkOverlap 字符重叠。
     *
     * @param content      原文
     * @param chunkSize    分块大小
     * @param chunkOverlap 重叠字符数
     * @return 分块文本列表
     */
    private List<String> chunkMarkdown(String content, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        // 按章节标题切分（# 开头的行）
        String[] lines = content.split("\n");
        StringBuilder currentSection = new StringBuilder();
        for (String line : lines) {
            if (line.trim().startsWith("#") && !currentSection.isEmpty()) {
                chunks.addAll(splitBySize(currentSection.toString(), chunkSize, chunkOverlap));
                currentSection = new StringBuilder();
            }
            currentSection.append(line).append("\n");
        }
        if (!currentSection.isEmpty()) {
            chunks.addAll(splitBySize(currentSection.toString(), chunkSize, chunkOverlap));
        }
        return chunks;
    }

    /**
     * Java 源码分块算法（简化版）
     * <p>
     * 按 public/private/protected 方法签名切分；类声明+import 作为类概述分块。
     * <p>
     * <b>简化说明</b>：未用 JavaParser AST 解析，仅用正则匹配方法签名，复杂场景可能不准确。
     *
     * @param content      原文
     * @param chunkSize    分块大小
     * @param chunkOverlap 重叠字符数
     * @return 分块文本列表
     */
    private List<String> chunkJavaCode(String content, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        // 简化：按方法签名行（含 public/private/protected 且以 ( 结尾的行）切分
        String[] lines = content.split("\n");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            boolean isMethodSignature = (trimmed.startsWith("public ") || trimmed.startsWith("private ")
                    || trimmed.startsWith("protected "))
                    && trimmed.contains("(") && !trimmed.contains("class ") && !trimmed.contains("interface ");
            if (isMethodSignature && !current.isEmpty()) {
                chunks.addAll(splitBySize(current.toString(), chunkSize, chunkOverlap));
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (!current.isEmpty()) {
            chunks.addAll(splitBySize(current.toString(), chunkSize, chunkOverlap));
        }
        return chunks;
    }

    /**
     * JavaDoc HTML 分块算法（简化版）
     * <p>
     * 按 {@code <class>}/{@code <method>} 标签切分；超长按段落二次切分。
     *
     * @param content      原文
     * @param chunkSize    分块大小
     * @param chunkOverlap 重叠字符数
     * @return 分块文本列表
     */
    private List<String> chunkJavaDoc(String content, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        // 简化：按 <h2>/<h3> 标签切分
        String[] sections = content.split("(?=<h[23])");
        for (String section : sections) {
            if (!section.trim().isEmpty()) {
                chunks.addAll(splitBySize(section, chunkSize, chunkOverlap));
            }
        }
        return chunks;
    }

    /**
     * 配置文件分块算法
     * <p>
     * 按顶层配置节（无缩进的 key，如 spring、server、mybatis）切分。
     *
     * @param content      原文
     * @param chunkSize    分块大小
     * @param chunkOverlap 重叠字符数
     * @return 分块文本列表
     */
    private List<String> chunkConfig(String content, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        String[] lines = content.split("\n");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            // 顶层配置节：非空行、不以空格/# 开头、含冒号
            boolean isTopLevel = !line.isEmpty() && !Character.isWhitespace(line.charAt(0))
                    && !line.startsWith("#") && !line.startsWith("//") && line.contains(":");
            if (isTopLevel && !current.isEmpty()) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        // 若分块过大，按 chunkSize 二次切分
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            result.addAll(splitBySize(chunk, chunkSize, chunkOverlap));
        }
        return result;
    }

    /**
     * 按大小切分文本（含重叠）
     *
     * @param text         原文
     * @param chunkSize    分块大小
     * @param chunkOverlap 重叠字符数
     * @return 分块列表
     */
    private List<String> splitBySize(String text, int chunkSize, int chunkOverlap) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        if (text.length() <= chunkSize) {
            result.add(text);
            return result;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            result.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            start = end - chunkOverlap;
            if (start < 0) {
                start = 0;
            }
        }
        return result;
    }
}