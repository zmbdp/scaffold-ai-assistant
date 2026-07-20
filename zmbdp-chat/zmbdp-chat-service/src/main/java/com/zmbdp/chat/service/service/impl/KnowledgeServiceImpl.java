package com.zmbdp.chat.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zmbdp.chat.api.knowledge.domain.dto.KnowledgeSourceReqDTO;
import com.zmbdp.chat.api.knowledge.domain.dto.SyncReqDTO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeDocumentVO;
import com.zmbdp.chat.api.knowledge.domain.vo.KnowledgeSourceVO;
import com.zmbdp.chat.api.knowledge.domain.vo.SyncResultVO;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库管理服务实现类
 * <p>
 * 知识库管理（B端 CRUD），管理知识源、文档；由 admin-service 通过 Feign 调用。
 * <p>
 * <b>参数校验</b>：DTO 上使用 Jakarta Validation 注解（@NotBlank/@Min/@Max），
 * Controller 层通过 @Valid 触发校验，Service 层不重复手写 if 校验。
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
        // 1. 校验 path 是否存在（复用脚手架 FileUtil，不直接使用 Java NIO）
        if (!FileUtil.exist(dto.getPath())) {
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
        // 校验 path 是否存在
        if (StringUtils.hasText(dto.getPath()) && !FileUtil.exist(dto.getPath())) {
            throw new ServiceException("知识源路径不存在：" + dto.getPath(), ResultCode.INVALID_PARA.getCode());
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
        SysAiKnowledgeSource update = new SysAiKnowledgeSource();
        update.setId(id);
        if (StringUtils.hasText(dto.getName())) {
            update.setName(dto.getName());
        }
        if (StringUtils.hasText(dto.getPath())) {
            update.setPath(dto.getPath());
        }
        if (StringUtils.hasText(dto.getType())) {
            update.setType(dto.getType());
        }
        if (dto.getEnabled() != null) {
            update.setEnabled(dto.getEnabled() ? DEFAULT_ENABLED : 0);
        }
        if (dto.getChunkSize() != null) {
            update.setChunkSize(dto.getChunkSize());
        }
        if (dto.getChunkOverlap() != null) {
            update.setChunkOverlap(dto.getChunkOverlap());
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
     * 触发知识同步
     * <p>
     * 委托给 {@link IKnowledgeLoaderService#syncKnowledge} 执行同步。
     *
     * @param dto 同步请求（含 sourceType、force 参数）
     * @return 同步结果统计
     */
    @Override
    public SyncResultVO sync(SyncReqDTO dto) {
        boolean force = dto != null && Boolean.TRUE.equals(dto.getForce());
        log.info("触发知识同步：force = {}", force);
        return knowledgeLoaderService.syncKnowledge(force);
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