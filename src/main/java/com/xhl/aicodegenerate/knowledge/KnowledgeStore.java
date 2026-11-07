package com.xhl.aicodegenerate.knowledge;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static com.xhl.aicodegenerate.knowledge.KnowledgeTypes.*;

/** 短事务保存业务事实。事务内不执行模型、向量索引或文件网络操作。 */
@Repository
public class KnowledgeStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KnowledgeProperties properties;
    private static final String VERSION_QUERY = "SELECT v.*,v.file_name AS name FROM knowledge_document_version v JOIN knowledge_document d ON d.id=v.document_id ";

    public KnowledgeStore(DataSource source, KnowledgeProperties properties) {
        this.jdbc = new JdbcTemplate(source);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.properties = properties;
    }

    public Work upload(long ownerId, String name, byte[] bytes, String requestId, Long documentId, Long expectedRevision) {
        requireOwner(ownerId); requireRequestId(requestId);
        String text = KnowledgeText.decode(name, bytes, properties.getMaxFileBytes());
        String type = documentId == null ? "UPLOAD" : "REPLACE";
        String hash = DigestUtil.sha256Hex(type + "\n" + documentId + "\n" + expectedRevision + "\n" + name + "\n" + DigestUtil.sha256Hex(bytes));
        return transaction.execute(status -> {
            Space space = lockSpace(ownerId);
            Work previous = replay(ownerId, type, requestId, hash, space);
            if (previous != null) return previous;
            long id = documentId == null ? IdUtil.getSnowflakeNextId() : documentId;
            int versionNo = 1;
            long oldReserved = 0;
            long activeBytes = 0;
            if (documentId != null) {
                Map<String, Object> document = ownedDocument(ownerId, id, true, false);
                if (expectedRevision == null || number(document, "revision") != expectedRevision) throw conflict("文档已变化，请刷新后重新替换");
                rejectProcessing(document);
                oldReserved = number(document, "reserved_bytes");
                activeBytes = activeBytes(document);
                versionNo = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM knowledge_document_version WHERE document_id=?", Integer.class, id);
            }
            long reserved = Math.max(activeBytes, bytes.length);
            checkQuota(ownerId, documentId == null ? 1 : 0, reserved - oldReserved);
            if (documentId == null) jdbc.update("INSERT INTO knowledge_document(id,owner_id,name,reserved_bytes,created_at) VALUES(?,?,?,?,?)", id, ownerId, name, reserved, now());
            long versionId = IdUtil.getSnowflakeNextId();
            String buildId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO knowledge_document_version(id,document_id,owner_id,file_name,version_no,status,byte_size,content_hash,raw_content,normalized_text,attempt,processing_build_id,lease_until,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    versionId, id, ownerId, name, versionNo, "PROCESSING", bytes.length, DigestUtil.sha256Hex(bytes), bytes, text, 1, buildId, now().plusMinutes(15), now());
            jdbc.update("UPDATE knowledge_document SET name=?,pending_version_id=?,reserved_bytes=?,revision=revision+1 WHERE id=?", name, versionId, reserved, id);
            Operation operation = insertOperation(ownerId, type, requestId, hash, id, versionId, 1, buildId);
            return new Work(operation, version(versionId), space.collectionKey(), true);
        });
    }

    public Work retry(long ownerId, long documentId, long versionId, String requestId) {
        requireOwner(ownerId); requireRequestId(requestId);
        String hash = DigestUtil.sha256Hex("RETRY\n" + documentId + "\n" + versionId);
        return transaction.execute(status -> {
            Space space = lockSpace(ownerId);
            Work previous = replay(ownerId, "RETRY", requestId, hash, space);
            if (previous != null) return previous;
            Map<String,Object> document = ownedDocument(ownerId, documentId, true, false);
            Version version = version(versionId);
            if (version.documentId() != documentId || !Objects.equals(nullableLong(document,"pending_version_id"), versionId)
                    || !"FAILED".equals(version.status()) || version.raw() == null) throw conflict("仅能重试当前失败版本");
            long reserved = Math.max(activeBytes(document), version.byteSize());
            checkQuota(ownerId, 0, reserved - number(document,"reserved_bytes"));
            String buildId = UUID.randomUUID().toString();
            int attempt = version.attempt() + 1;
            jdbc.update("UPDATE knowledge_document_version SET status='PROCESSING',attempt=?,processing_build_id=?,lease_until=?,error_code=NULL WHERE id=?", attempt, buildId, now().plusMinutes(15), versionId);
            jdbc.update("UPDATE knowledge_document SET reserved_bytes=?,revision=revision+1 WHERE id=?", reserved, documentId);
            Operation operation = insertOperation(ownerId,"RETRY",requestId,hash,documentId,versionId,attempt,buildId);
            return new Work(operation, version(versionId), space.collectionKey(),true);
        });
    }

    public boolean publish(Operation operation, String profile, int count) {
        if (profile == null || profile.isBlank() || count <= 0) throw conflict("索引未完整生成");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            Space space = lockSpace(operation.ownerId());
            Map<String,Object> document = ownedDocument(operation.ownerId(),operation.documentId(),true,true);
            Version version = version(operation.versionId());
            if (!current(document,version,operation)) return false;
            if (space.profile() != null && !space.profile().equals(profile)) throw conflict("向量模型配置已变化，请先重建索引");
            jdbc.update("UPDATE knowledge_space SET embedding_profile=? WHERE owner_id=?",profile,operation.ownerId());
            jdbc.update("UPDATE knowledge_document_version SET status='READY',embedding_profile=?,chunk_count=?,published_build_id=?,error_code=NULL WHERE id=?",
                    profile,count,operation.buildId(),version.id());
            jdbc.update("UPDATE knowledge_document SET active_version_id=?,reserved_bytes=?,revision=revision+1 WHERE id=?",version.id(),version.byteSize(),document.get("id"));
            jdbc.update("UPDATE knowledge_operation SET status='SUCCEEDED' WHERE id=?",operation.id());
            return true;
        }));
    }

    public void fail(Operation operation, String safeCode) {
        transaction.executeWithoutResult(status -> {
            lockSpace(operation.ownerId());
            Map<String,Object> document = ownedDocument(operation.ownerId(),operation.documentId(),true,true);
            Version version = version(operation.versionId());
            if (!current(document,version,operation)) return;
            String code = safeCode != null && safeCode.matches("[A-Z_]{1,64}") ? safeCode : "PROCESSING_FAILED";
            jdbc.update("UPDATE knowledge_document_version SET status='FAILED',error_code=? WHERE id=?",code,version.id());
            jdbc.update("UPDATE knowledge_operation SET status='FAILED',result_code=? WHERE id=?",code,operation.id());
            long reserved = nullableLong(document,"active_version_id") == null ? version.byteSize() : activeBytes(document);
            jdbc.update("UPDATE knowledge_document SET reserved_bytes=?,revision=revision+1 WHERE id=?",reserved,operation.documentId());
        });
    }

    public void delete(long ownerId, long documentId) {
        transaction.executeWithoutResult(status -> {
            lockSpace(ownerId);
            Map<String,Object> document = ownedDocument(ownerId,documentId,true,true);
            if (document.get("deleted_at") != null) return;
            jdbc.update("UPDATE knowledge_document SET deleted_at=?,reserved_bytes=0,revision=revision+1 WHERE id=?",now(),documentId);
            jdbc.update("UPDATE knowledge_document_version SET raw_content=NULL,normalized_text=NULL,status='DELETED' WHERE document_id=?",documentId);
            jdbc.update("UPDATE knowledge_operation SET status='DELETED' WHERE document_id=? AND status='PROCESSING'",documentId);
            jdbc.update("UPDATE knowledge_reference_item SET text=NULL,visibility='DELETED' WHERE document_id=?",documentId);
        });
    }

    public List<DocumentView> list(long ownerId) {
        requireOwner(ownerId);
        return jdbc.query("SELECT d.*,a.version_no AS active_no,p.version_no AS pending_no,p.status,p.error_code,p.byte_size FROM knowledge_document d LEFT JOIN knowledge_document_version a ON a.id=d.active_version_id LEFT JOIN knowledge_document_version p ON p.id=d.pending_version_id WHERE d.owner_id=? AND d.deleted_at IS NULL ORDER BY d.created_at DESC,d.id DESC",
                (r,i) -> new DocumentView(r.getLong("id"),r.getString("name"),nullableLong(r,"active_version_id"),nullableInt(r,"active_no"),nullableLong(r,"pending_version_id"),nullableInt(r,"pending_no"),r.getString("status"),r.getString("error_code"),r.getLong("revision"),r.getLong("byte_size"),millis(r,"created_at")),ownerId);
    }

    public Quota quota(long ownerId) {
        requireOwner(ownerId);
        Map<String,Object> totals = jdbc.queryForMap("SELECT COUNT(*) AS cnt,COALESCE(SUM(reserved_bytes),0) AS used FROM knowledge_document WHERE owner_id=? AND deleted_at IS NULL",ownerId);
        return new Quota(number(totals,"used"),(int)number(totals,"cnt"),properties.getMaxFileBytes(),properties.getMaxTotalBytes(),properties.getMaxDocuments());
    }

    public List<Version> activeVersions(long ownerId) {
        requireOwner(ownerId);
        return jdbc.query(VERSION_QUERY+"WHERE d.owner_id=? AND d.deleted_at IS NULL AND d.active_version_id=v.id AND v.status='READY' ORDER BY d.id",VERSION_MAPPER,ownerId);
    }

    public Space space(long ownerId) {
        requireOwner(ownerId);
        List<Space> rows = jdbc.query("SELECT * FROM knowledge_space WHERE owner_id=?",(r,i)->new Space(r.getLong("owner_id"),r.getString("collection_key"),r.getString("embedding_profile")),ownerId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 将当前版本与删除操作序列化，只接受真实版本中存在的片段。 */
    public List<Source> captureSources(long ownerId, List<Source> candidates) {
        return transaction.execute(status -> {
            Map<Long, Map<String,Object>> documents = new HashMap<>();
            candidates.stream().map(Source::documentId).distinct().sorted().forEach(id -> documents.put(id,ownedDocument(ownerId,id,true,true)));
            List<Source> accepted = new ArrayList<>();
            for (Source source : candidates) {
                Map<String,Object> document = documents.get(source.documentId());
                if (document.get("deleted_at") != null || !Objects.equals(nullableLong(document,"active_version_id"),source.versionId())) continue;
                Version version = version(source.versionId());
                if (!Objects.equals(version.publishedBuildId(),source.buildId()) || version.text() == null
                        || source.text() == null || source.text().isBlank() || !version.text().contains(source.text())) continue;
                accepted.add(new Source(version.documentId(),version.id(),version.versionNo(),version.name(),version.publishedBuildId(),source.chunkId(),source.text(),source.score()));
            }
            return List.copyOf(accepted);
        });
    }

    public void beginReference(String taskId, long ownerId, long appId, Long userMessageId, Long aiMessageId) {
        requireRequestId(taskId);
        transaction.executeWithoutResult(status -> {
            lockApp(ownerId,appId);
            List<Map<String,Object>> existing = jdbc.queryForList("SELECT * FROM knowledge_task_reference WHERE task_id=? FOR UPDATE",taskId);
            if (!existing.isEmpty()) { checkReferenceOwner(existing.getFirst(),ownerId,appId); return; }
            jdbc.update("INSERT INTO knowledge_task_reference(task_id,app_id,owner_id,user_message_id,ai_message_id,state,created_at) VALUES(?,?,?,?,?,'REQUIRED',?)",taskId,appId,ownerId,userMessageId,aiMessageId,now());
        });
    }

    public void saveReference(String taskId, long ownerId, long appId, List<Source> sources) {
        if (sources.isEmpty()) throw conflict("没有可保存的参考资料");
        transaction.executeWithoutResult(status -> {
            lockApp(ownerId,appId);
            Map<String,Object> head = referenceHead(taskId,true); checkReferenceOwner(head,ownerId,appId);
            String digest = DigestUtil.sha256Hex(sources.toString());
            if (head.get("snapshot_digest") != null) {
                if (!digest.equals(head.get("snapshot_digest"))) throw conflict("任务资料快照不可变更");
                return;
            }
            Map<Long,Map<String,Object>> documents = new HashMap<>();
            sources.stream().map(Source::documentId).distinct().sorted().forEach(id -> documents.put(id,ownedDocument(ownerId,id,true,true)));
            for (int i=0;i<sources.size();i++) {
                Source source=sources.get(i); boolean deleted=documents.get(source.documentId()).get("deleted_at")!=null;
                jdbc.update("INSERT INTO knowledge_reference_item(task_id,ordinal_no,document_id,version_id,document_name,version_no,build_id,chunk_id,text,content_hash,score,visibility) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        taskId,i,source.documentId(),source.versionId(),source.documentName(),source.versionNo(),source.buildId(),source.chunkId(),deleted?null:source.text(),DigestUtil.sha256Hex(source.text()),source.score(),deleted?"DELETED":"VISIBLE");
            }
            jdbc.update("UPDATE knowledge_task_reference SET state=CASE WHEN submitted_at IS NULL THEN 'PREPARED' ELSE 'SUBMITTED' END,snapshot_digest=? WHERE task_id=?",digest,taskId);
        });
    }

    public void markSubmitted(String taskId,long ownerId,long appId) {
        transaction.executeWithoutResult(status -> {
            lockApp(ownerId,appId);
            Map<String,Object> head=referenceHead(taskId,true);checkReferenceOwner(head,ownerId,appId);
            jdbc.update("UPDATE knowledge_task_reference SET state=CASE WHEN snapshot_digest IS NULL THEN 'INCOMPLETE' ELSE 'SUBMITTED' END,submitted_at=COALESCE(submitted_at,?) WHERE task_id=?",now(),taskId);
        });
    }

    public void markReferenceFailure(String taskId,long ownerId,long appId,String state) {
        if (!Set.of("FAILED","SEND_UNCONFIRMED","INCOMPLETE").contains(state)) throw conflict("参考记录状态无效");
        transaction.executeWithoutResult(status -> {
            lockApp(ownerId,appId);checkReferenceOwner(referenceHead(taskId,true),ownerId,appId);
            jdbc.update("UPDATE knowledge_task_reference SET state=? WHERE task_id=? AND state<>'SUBMITTED'",state,taskId);
        });
    }

    public Reference reference(long ownerId,String taskId) {
        return transaction.execute(status -> {
            Map<String,Object> initial=referenceHead(taskId,false);
            long appId=number(initial,"app_id");lockApp(ownerId,appId);checkReferenceOwner(initial,ownerId,appId);
            Map<String,Object> head=referenceHead(taskId,false);
            List<Source> items=jdbc.query("SELECT i.*,CASE WHEN d.deleted_at IS NOT NULL OR i.visibility='DELETED' THEN NULL ELSE i.text END AS visible_text FROM knowledge_reference_item i JOIN knowledge_document d ON d.id=i.document_id WHERE i.task_id=? ORDER BY i.ordinal_no",
                    (r,i)->new Source(r.getLong("document_id"),r.getLong("version_id"),r.getInt("version_no"),r.getString("document_name"),r.getString("build_id"),r.getString("chunk_id"),r.getString("visible_text"),r.getDouble("score")),taskId);
            return referenceView(head,items);
        });
    }

    public List<Reference> references(long ownerId,long appId,int page,int pageSize) {
        if(page<1||pageSize<1||pageSize>50||(long)page*pageSize>1_000_000)throw conflict("分页参数无效");
        return transaction.execute(status -> {
            lockApp(ownerId,appId);
            return jdbc.queryForList("SELECT * FROM knowledge_task_reference WHERE app_id=? AND owner_id=? ORDER BY created_at DESC,task_id DESC LIMIT ? OFFSET ?",appId,ownerId,pageSize,(page-1)*pageSize)
                    .stream().map(head->referenceView(head,List.of())).toList();
        });
    }

    public void clearDeletedApplicationReferences() {
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE i FROM knowledge_reference_item i JOIN knowledge_task_reference r ON r.task_id=i.task_id LEFT JOIN app a ON a.id=r.app_id WHERE a.id IS NULL OR a.isDelete<>0");
            jdbc.update("DELETE r FROM knowledge_task_reference r LEFT JOIN app a ON a.id=r.app_id WHERE a.id IS NULL OR a.isDelete<>0");
        });
    }

    public List<Work> expiredProcessing(int limit) {
        return jdbc.query("SELECT o.* FROM knowledge_operation o JOIN knowledge_document_version v ON v.id=o.version_id WHERE o.status='PROCESSING' AND v.processing_build_id=o.build_id AND v.lease_until<? LIMIT ?",OPERATION_MAPPER,now(),limit)
                .stream().map(o->new Work(o,version(o.versionId()),space(o.ownerId()).collectionKey(),false)).toList();
    }

    public List<Work> obsoleteBuilds(int limit) {
        return obsoleteBuilds(0, limit);
    }

    /** 游标完成一轮后从头扫描，既不饿死后续批次，也能清理迟到的远端写入。 */
    public List<Work> obsoleteBuilds(long afterId, int limit) {
        return jdbc.query("SELECT o.* FROM knowledge_operation o JOIN knowledge_document_version v ON v.id=o.version_id JOIN knowledge_document d ON d.id=o.document_id WHERE o.id>? AND (d.deleted_at IS NOT NULL OR (o.status<>'PROCESSING' AND (d.active_version_id IS NULL OR d.active_version_id<>v.id OR v.published_build_id IS NULL OR v.published_build_id<>o.build_id))) ORDER BY o.id LIMIT ?",OPERATION_MAPPER,afterId,limit)
                .stream().map(o->new Work(o,version(o.versionId()),space(o.ownerId()).collectionKey(),false)).toList();
    }

    public boolean isCurrent(Operation operation) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document d JOIN knowledge_document_version v ON v.id=d.pending_version_id WHERE d.id=? AND d.owner_id=? AND d.deleted_at IS NULL AND v.id=? AND v.status='PROCESSING' AND v.processing_build_id=? AND v.attempt=? AND v.lease_until>?",
                Integer.class, operation.documentId(), operation.ownerId(), operation.versionId(), operation.buildId(), operation.attempt(), now());
        return count != null && count == 1;
    }

    private Operation insertOperation(long ownerId,String type,String requestId,String hash,long documentId,long versionId,int attempt,String buildId) {
        long id=IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO knowledge_operation(id,owner_id,operation_type,client_request_id,request_hash,document_id,version_id,attempt,build_id,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,'PROCESSING',?)",id,ownerId,type,requestId,hash,documentId,versionId,attempt,buildId,now());
        return new Operation(id,ownerId,type,requestId,hash,documentId,versionId,attempt,buildId,"PROCESSING");
    }

    private Work replay(long ownerId,String type,String requestId,String hash,Space space) {
        List<Operation> operations=jdbc.query("SELECT * FROM knowledge_operation WHERE owner_id=? AND operation_type=? AND client_request_id=?",OPERATION_MAPPER,ownerId,type,requestId);
        if(operations.isEmpty())return null;
        Operation operation=operations.getFirst();
        if(!operation.requestHash().equals(hash))throw conflict("相同请求标识不能用于不同的上传或重试");
        return new Work(operation,version(operation.versionId()),space.collectionKey(),false);
    }

    private Space lockSpace(long ownerId) {
        requireOwner(ownerId);
        // 重复主键时直接取得排他锁，避免 INSERT IGNORE 的共享锁在并发上传时升级死锁。
        jdbc.update("INSERT INTO knowledge_space(owner_id,collection_key,created_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE owner_id=?",ownerId,"kb_"+UUID.randomUUID().toString().replace("-",""),now(),ownerId);
        jdbc.queryForObject("SELECT owner_id FROM knowledge_space WHERE owner_id=? FOR UPDATE",Long.class,ownerId);
        return space(ownerId);
    }

    private Map<String,Object> ownedDocument(long ownerId,long id,boolean lock,boolean allowDeleted) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM knowledge_document WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"文档不存在");
        Map<String,Object> row=rows.getFirst();
        if(number(row,"owner_id")!=ownerId)throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"仅能操作自己的知识文档");
        if(!allowDeleted&&row.get("deleted_at")!=null)throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"文档已删除");
        return row;
    }

    private void rejectProcessing(Map<String,Object> document) {
        Long pending=nullableLong(document,"pending_version_id");
        if(pending!=null&&"PROCESSING".equals(version(pending).status()))throw conflict("文档正在处理，请等待完成后替换");
    }

    private long activeBytes(Map<String,Object> document) {
        Long id=nullableLong(document,"active_version_id");return id==null?0:version(id).byteSize();
    }

    private void checkQuota(long ownerId,int addedDocuments,long addedBytes) {
        Quota quota=quota(ownerId);
        if(quota.documents()+addedDocuments>properties.getMaxDocuments()||quota.usedBytes()+addedBytes>properties.getMaxTotalBytes())throw conflict("知识库数量或总容量已达上限，请先删除不需要的文档");
    }

    private boolean current(Map<String,Object> document,Version version,Operation operation) {
        return document.get("deleted_at")==null&&Objects.equals(nullableLong(document,"pending_version_id"),version.id())
                && "PROCESSING".equals(version.status())&&version.attempt()==operation.attempt()&&Objects.equals(version.buildId(),operation.buildId());
    }

    private Version version(long id) {
        List<Version> rows=jdbc.query(VERSION_QUERY+"WHERE v.id=?",VERSION_MAPPER,id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"文档版本不存在");
        return rows.getFirst();
    }

    private void lockApp(long ownerId,long appId) {
        requireOwner(ownerId);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,userId,isDelete FROM app WHERE id=? FOR UPDATE",appId);
        if(rows.isEmpty()||number(rows.getFirst(),"isDelete")!=0)throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"应用不存在或已删除");
        if(number(rows.getFirst(),"userId")!=ownerId)throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"仅应用所有者可以查看参考资料");
    }

    private Map<String,Object> referenceHead(String taskId,boolean lock) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM knowledge_task_reference WHERE task_id=?"+(lock?" FOR UPDATE":""),taskId);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"参考资料记录不存在");return rows.getFirst();
    }

    private void checkReferenceOwner(Map<String,Object> head,long ownerId,long appId) {
        if(number(head,"owner_id")!=ownerId||number(head,"app_id")!=appId)throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"参考资料不属于当前用户和应用");
    }

    private Reference referenceView(Map<String,Object> head,List<Source> items) {
        Object date=head.get("created_at");
        long created=date instanceof LocalDateTime value?value.toInstant(ZoneOffset.UTC).toEpochMilli():((java.sql.Timestamp)date).toLocalDateTime().toInstant(ZoneOffset.UTC).toEpochMilli();
        return new Reference((String)head.get("task_id"),number(head,"app_id"),nullableLong(head,"user_message_id"),nullableLong(head,"ai_message_id"),(String)head.get("state"),created,List.copyOf(items));
    }

    private static final RowMapper<Version> VERSION_MAPPER=(r,i)->new Version(r.getLong("id"),r.getLong("document_id"),r.getLong("owner_id"),r.getString("name"),r.getInt("version_no"),r.getString("status"),r.getLong("byte_size"),r.getBytes("raw_content"),r.getString("normalized_text"),r.getString("embedding_profile"),r.getInt("attempt"),r.getString("processing_build_id"),r.getString("published_build_id"),r.getInt("chunk_count"));
    private static final RowMapper<Operation> OPERATION_MAPPER=(r,i)->new Operation(r.getLong("id"),r.getLong("owner_id"),r.getString("operation_type"),r.getString("client_request_id"),r.getString("request_hash"),r.getLong("document_id"),r.getLong("version_id"),r.getInt("attempt"),r.getString("build_id"),r.getString("status"));
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Long nullableLong(Map<String,Object> row,String key){return row.get(key)==null?null:number(row,key);}
    private static Long nullableLong(ResultSet row,String key)throws SQLException{Object value=row.getObject(key);return value==null?null:((Number)value).longValue();}
    private static Integer nullableInt(ResultSet row,String key)throws SQLException{Long value=nullableLong(row,key);return value==null?null:value.intValue();}
    private static long millis(ResultSet row,String key)throws SQLException{return row.getObject(key,LocalDateTime.class).toInstant(ZoneOffset.UTC).toEpochMilli();}
    private static LocalDateTime now(){return LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC);}
    private static void requireOwner(long ownerId){if(ownerId<=0)throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);}
    private static void requireRequestId(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw conflict("请提供稳定的请求标识");}
    private static BusinessException conflict(String message){return new BusinessException(ErrorCode.PARAMS_ERROR,message);}
}
