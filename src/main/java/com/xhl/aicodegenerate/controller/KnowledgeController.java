package com.xhl.aicodegenerate.controller;

import com.xhl.aicodegenerate.common.BaseResponse;
import com.xhl.aicodegenerate.common.ResultUtils;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.knowledge.*;
import com.xhl.aicodegenerate.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import static com.xhl.aicodegenerate.knowledge.KnowledgeTypes.*;

/** 仅从登录态取得所有者；DTO 不含原文、collection、构建标识或内部存储对象。 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {
    private final KnowledgeStore store;
    private final KnowledgeDocumentService documents;
    private final UserService users;
    private final KnowledgeProperties properties;

    public KnowledgeController(KnowledgeStore store, KnowledgeDocumentService documents, UserService users, KnowledgeProperties properties) {
        this.store = store; this.documents = documents; this.users = users; this.properties = properties;
    }

    public record Document(String id, String name, String activeVersionId, Integer activeVersionNo,
                           String pendingVersionId, Integer pendingVersionNo, String status, String errorCode,
                           long revision, long byteSize, long createdAt) { }
    public record DocumentPage(List<Document> records, int total, Quota quota, boolean enabled) { }
    public record Processing(String documentId, String versionId, String operationId, String status) { }
    public record Retry(String versionId, String clientRequestId) { }
    public record Fragment(String documentName, int versionNo, String text) { }
    public record ReferenceView(String taskId, String appId, String userMessageId, String aiMessageId,
                                String state, long createdAt, List<Fragment> items) { }

    @GetMapping("/documents")
    public BaseResponse<DocumentPage> list(@RequestParam(defaultValue = "1") int pageNum,
                                           @RequestParam(defaultValue = "20") int pageSize, HttpServletRequest request) {
        if (pageNum < 1 || pageSize < 1 || pageSize > 50 || (long) pageNum * pageSize > 1_000_000) throw invalid();
        long owner = users.getLoginUser(request).getId();
        var all = store.list(owner);
        var records = all.stream().skip((long) (pageNum - 1) * pageSize).limit(pageSize)
                .map(d -> new Document(id(d.id()), d.name(), id(d.activeVersionId()), d.activeVersionNo(), id(d.pendingVersionId()),
                        d.pendingVersionNo(), d.status(), d.errorCode(), d.revision(), d.byteSize(), d.createdAt())).toList();
        return ResultUtils.success(new DocumentPage(records, all.size(), store.quota(owner), properties.isEnabled()));
    }

    @PostMapping("/documents")
    public BaseResponse<Processing> upload(@RequestPart MultipartFile file, @RequestParam String clientRequestId, HttpServletRequest request) {
        long owner = users.getLoginUser(request).getId();
        return ResultUtils.success(view(documents.upload(owner, file.getOriginalFilename(), bytes(file), clientRequestId, null, null)));
    }

    @PostMapping("/documents/{documentId}/versions")
    public BaseResponse<Processing> replace(@PathVariable long documentId, @RequestPart MultipartFile file,
                                            @RequestParam String clientRequestId, @RequestParam long expectedRevision, HttpServletRequest request) {
        long owner = users.getLoginUser(request).getId();
        return ResultUtils.success(view(documents.upload(owner, file.getOriginalFilename(), bytes(file), clientRequestId, documentId, expectedRevision)));
    }

    @PostMapping("/documents/{documentId}/retry")
    public BaseResponse<Processing> retry(@PathVariable long documentId, @RequestBody Retry input, HttpServletRequest request) {
        long owner = users.getLoginUser(request).getId();
        long version;
        try { version = Long.parseLong(input.versionId()); } catch (RuntimeException error) { throw invalid(); }
        return ResultUtils.success(view(documents.retry(owner, documentId, version, input.clientRequestId())));
    }

    @DeleteMapping("/documents/{documentId}")
    public BaseResponse<Boolean> delete(@PathVariable long documentId, HttpServletRequest request) {
        store.delete(users.getLoginUser(request).getId(), documentId);
        return ResultUtils.success(true);
    }

    @GetMapping("/references")
    public BaseResponse<List<ReferenceView>> references(@RequestParam long appId, @RequestParam(defaultValue = "1") int pageNum,
                                                        @RequestParam(defaultValue = "20") int pageSize, HttpServletRequest request) {
        return ResultUtils.success(store.references(users.getLoginUser(request).getId(), appId, pageNum, pageSize).stream().map(this::view).toList());
    }

    @GetMapping("/references/{taskId}")
    public BaseResponse<ReferenceView> reference(@PathVariable String taskId, HttpServletRequest request) {
        return ResultUtils.success(view(store.reference(users.getLoginUser(request).getId(), taskId)));
    }

    @ExceptionHandler(DataAccessException.class)
    public BaseResponse<?> unavailable() { return ResultUtils.error(ErrorCode.OPERATION_ERROR, "知识库暂不可用，请检查数据库迁移或稍后重试"); }

    private byte[] bytes(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > properties.getMaxFileBytes()) throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件为空或超过单文件容量限制");
        try (var stream = file.getInputStream()) { return stream.readNBytes(Math.toIntExact(properties.getMaxFileBytes()) + 1); }
        catch (IOException error) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "读取上传文件失败，请重新选择文件"); }
    }

    private Processing view(Work work) {
        return new Processing(id(work.operation().documentId()), id(work.operation().versionId()), id(work.operation().id()), work.operation().status());
    }
    private ReferenceView view(Reference reference) {
        return new ReferenceView(reference.taskId(), id(reference.appId()), id(reference.userMessageId()), id(reference.aiMessageId()), reference.state(), reference.createdAt(),
                reference.items().stream().map(s -> new Fragment(s.documentName(), s.versionNo(), s.text())).toList());
    }
    private static String id(Long value) { return value == null ? null : value.toString(); }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.PARAMS_ERROR, "知识库请求参数无效"); }
}
