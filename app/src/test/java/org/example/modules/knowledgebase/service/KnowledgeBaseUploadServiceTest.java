package org.example.modules.knowledgebase.service;

import org.example.common.exception.BusinessException;
import org.example.infrastructure.file.FileHashService;
import org.example.infrastructure.file.FileStorageService;
import org.example.infrastructure.file.FileValidationService;
import org.example.modules.knowledgebase.listener.VectorizeStreamProducer;
import org.example.modules.knowledgebase.model.KnowledgeBaseEntity;
import org.example.modules.knowledgebase.model.VectorStatus;
import org.example.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库上传服务测试")
class KnowledgeBaseUploadServiceTest {
    private static final MockMultipartFile FILE = new MockMultipartFile(
            "file",
            "guide.pdf",
            "application/pdf",
            "knowledge base content".getBytes()
    );
    private static final String NAME = "  Java 面试题库  ";
    private static final String CATEGORY = "后端";
    private static final String FILE_HASH = "hash-001";
    private static final String STORAGE_KEY = "knowledgebase/2026/05/22/guide.pdf";
    private static final String STORAGE_URL = "http://localhost:9000/bucket/guide.pdf";
    private static final String PARSED_CONTENT = "parsed knowledge base";
    private static final StreamMessageId MESSAGE_ID = new StreamMessageId(1L, 2L);

    @Mock
    private KnowledgeBaseParseService parseService;

    @Mock
    private KnowledgeBasePersistenceService persistenceService;

    @Mock
    private FileStorageService storageService;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private FileValidationService fileValidationService;

    @Mock
    private FileHashService fileHashService;

    @Mock
    private VectorizeStreamProducer vectorizeStreamProducer;

    @InjectMocks
    private KnowledgeBaseUploadService knowledgeBaseUploadService;

    @Nested
    @DisplayName("上传")
    class Upload {

        /**
         * 验证首次上传会完成校验、存储、保存与异步投递全流程。
         */
        @Test
        @DisplayName("首次上传应返回完整上传结果")
        void shouldUploadKnowledgeBaseSuccessfully() {
            KnowledgeBaseEntity knowledgeBase = buildKnowledgeBaseEntity(1L);
            when(parseService.detectContentType(FILE)).thenReturn("application/pdf");
            when(fileHashService.calculateHash(FILE)).thenReturn(FILE_HASH);
            when(knowledgeBaseRepository.existsByFileHash(FILE_HASH)).thenReturn(false);
            when(storageService.generateObjectKey("knowledgebase", FILE.getOriginalFilename()))
                    .thenReturn(STORAGE_KEY);
            when(storageService.upload(FILE, STORAGE_KEY)).thenReturn(STORAGE_URL);
            when(persistenceService.save(FILE, "Java 面试题库", CATEGORY, STORAGE_KEY, STORAGE_URL, FILE_HASH))
                    .thenReturn(knowledgeBase);
            when(parseService.parse(FILE)).thenReturn(PARSED_CONTENT);
            when(vectorizeStreamProducer.sendVectorizeTask(1L, PARSED_CONTENT)).thenReturn(MESSAGE_ID);

            Map<String, Object> result = knowledgeBaseUploadService.upload(FILE, NAME, CATEGORY);

            @SuppressWarnings("unchecked")
            Map<String, Object> knowledgeBaseMap = (Map<String, Object>) result.get("knowledgeBase");
            @SuppressWarnings("unchecked")
            Map<String, Object> storageMap = (Map<String, Object>) result.get("storage");

            assertThat(result.get("duplicate")).isEqualTo(false);
            assertThat(knowledgeBaseMap.get("id")).isEqualTo(1L);
            assertThat(knowledgeBaseMap.get("name")).isEqualTo("Java 面试题库");
            assertThat(knowledgeBaseMap.get("category")).isEqualTo(CATEGORY);
            assertThat(knowledgeBaseMap.get("fileSize")).isEqualTo(FILE.getSize());
            assertThat(knowledgeBaseMap.get("contentLength")).isEqualTo(PARSED_CONTENT.length());
            assertThat(knowledgeBaseMap.get("vectorStatus")).isEqualTo(VectorStatus.PENDING.name());
            assertThat(storageMap.get("fileKey")).isEqualTo(STORAGE_KEY);
            assertThat(storageMap.get("fileUrl")).isEqualTo(STORAGE_URL);

            verify(fileValidationService).validateKnowledgeBaseFile(FILE);
            verify(parseService).detectContentType(FILE);
            verify(fileHashService).calculateHash(FILE);
            verify(storageService).upload(FILE, STORAGE_KEY);
            verify(vectorizeStreamProducer).sendVectorizeTask(1L, PARSED_CONTENT);
        }

        /**
         * 验证重复上传时当前实现仍会继续执行上传主流程，但会先调用重复文件处理逻辑。
         */
        @Test
        @DisplayName("重复上传时应先处理重复文件")
        void shouldHandleDuplicateBeforeContinuingUpload() {
            KnowledgeBaseEntity saved = buildKnowledgeBaseEntity(1L);
            when(parseService.detectContentType(FILE)).thenReturn("application/pdf");
            when(fileHashService.calculateHash(FILE)).thenReturn(FILE_HASH);
            when(knowledgeBaseRepository.existsByFileHash(FILE_HASH)).thenReturn(true);
            when(persistenceService.handleDuplicateKnowledgeBase(FILE_HASH)).thenReturn(Map.of(
                    "knowledgeBase", Map.of("id", 9L),
                    "storage", Map.of("fileKey", STORAGE_KEY, "fileUrl", STORAGE_URL),
                    "duplicate", true
            ));
            when(storageService.generateObjectKey("knowledgebase", FILE.getOriginalFilename()))
                    .thenReturn(STORAGE_KEY);
            when(storageService.upload(FILE, STORAGE_KEY)).thenReturn(STORAGE_URL);
            when(persistenceService.save(FILE, "Java 面试题库", CATEGORY, STORAGE_KEY, STORAGE_URL, FILE_HASH))
                    .thenReturn(saved);
            when(parseService.parse(FILE)).thenReturn(PARSED_CONTENT);
            when(vectorizeStreamProducer.sendVectorizeTask(1L, PARSED_CONTENT)).thenReturn(MESSAGE_ID);

            Map<String, Object> result = knowledgeBaseUploadService.upload(FILE, NAME, CATEGORY);

            assertThat(result.get("duplicate")).isEqualTo(false);
            verify(persistenceService).handleDuplicateKnowledgeBase(FILE_HASH);
            verify(persistenceService).save(FILE, "Java 面试题库", CATEGORY, STORAGE_KEY, STORAGE_URL, FILE_HASH);
        }

        /**
         * 验证名称为空时会回退为原始文件名。
         */
        @Test
        @DisplayName("知识库名称为空时应使用原始文件名")
        void shouldUseOriginalFilenameWhenNameBlank() {
            KnowledgeBaseEntity knowledgeBase = buildKnowledgeBaseEntity(1L);
            when(parseService.detectContentType(FILE)).thenReturn("application/pdf");
            when(fileHashService.calculateHash(FILE)).thenReturn(FILE_HASH);
            when(knowledgeBaseRepository.existsByFileHash(FILE_HASH)).thenReturn(false);
            when(storageService.generateObjectKey("knowledgebase", FILE.getOriginalFilename()))
                    .thenReturn(STORAGE_KEY);
            when(storageService.upload(FILE, STORAGE_KEY)).thenReturn(STORAGE_URL);
            when(persistenceService.save(
                    FILE,
                    FILE.getOriginalFilename(),
                    CATEGORY,
                    STORAGE_KEY,
                    STORAGE_URL,
                    FILE_HASH
            )).thenReturn(knowledgeBase);
            when(parseService.parse(FILE)).thenReturn(PARSED_CONTENT);
            when(vectorizeStreamProducer.sendVectorizeTask(1L, PARSED_CONTENT)).thenReturn(MESSAGE_ID);

            knowledgeBaseUploadService.upload(FILE, "  ", CATEGORY);

            verify(persistenceService).save(
                    FILE,
                    FILE.getOriginalFilename(),
                    CATEGORY,
                    STORAGE_KEY,
                    STORAGE_URL,
                    FILE_HASH
            );
        }
    }

    @Nested
    @DisplayName("重试向量化")
    class RetryVectorization {

        /**
         * 验证手动重试时会重新解析对象存储文件并重新发送任务。
         */
        @Test
        @DisplayName("重试向量化应重新发送任务")
        void shouldRetryVectorizationSuccessfully() {
            KnowledgeBaseEntity knowledgeBase = buildKnowledgeBaseEntity(1L);
            knowledgeBase.setStorageKey(STORAGE_KEY);
            knowledgeBase.setOriginalFilename("guide.pdf");
            when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.of(knowledgeBase));
            when(parseService.parse(STORAGE_KEY, "guide.pdf")).thenReturn(PARSED_CONTENT);
            when(vectorizeStreamProducer.sendVectorizeTask(1L, PARSED_CONTENT)).thenReturn(MESSAGE_ID);

            StreamMessageId messageId = knowledgeBaseUploadService.retryVectorization(1L);

            assertThat(messageId).isEqualTo(MESSAGE_ID);
            verify(parseService).parse(STORAGE_KEY, "guide.pdf");
            verify(vectorizeStreamProducer).sendVectorizeTask(1L, PARSED_CONTENT);
        }
    }

    @Nested
    @DisplayName("异常")
    class Validation {

        /**
         * 这里直接模拟检测结果为空，覆盖私有内容类型校验分支。
         */
        @Test
        @DisplayName("检测出的内容类型为空时应抛出业务异常")
        void shouldThrowWhenDetectedContentTypeBlank() {
            when(parseService.detectContentType(FILE)).thenReturn(" ");

            assertThatThrownBy(() -> knowledgeBaseUploadService.upload(FILE, NAME, CATEGORY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库文件类型不能为空");
        }

        /**
         * 文件名为空时私有校验也应阻止继续处理。
         */
        @Test
        @DisplayName("文件名为空时应抛出业务异常")
        void shouldThrowWhenFilenameBlank() {
            MockMultipartFile blankNameFile = new MockMultipartFile(
                    "file",
                    "",
                    "application/pdf",
                    "content".getBytes()
            );
            when(parseService.detectContentType(blankNameFile)).thenReturn("application/pdf");

            assertThatThrownBy(() -> knowledgeBaseUploadService.upload(blankNameFile, NAME, CATEGORY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库文件名不能为空");
        }

        /**
         * 非法知识库 ID 不应进入数据库查询。
         */
        @Test
        @DisplayName("重试向量化时非法ID应抛出业务异常")
        void shouldThrowWhenRetryIdInvalid() {
            assertThatThrownBy(() -> knowledgeBaseUploadService.retryVectorization(0L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库ID不合法");

            verify(knowledgeBaseRepository, never()).findById(0L);
        }

        /**
         * 不存在的知识库无法重新向量化。
         */
        @Test
        @DisplayName("重试向量化时知识库不存在应抛出业务异常")
        void shouldThrowWhenRetryKnowledgeBaseNotFound() {
            when(knowledgeBaseRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> knowledgeBaseUploadService.retryVectorization(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("知识库不存在");
        }
    }

    /**
     * 构造一个可复用的知识库实体，避免每个用例重复拼装。
     */
    private KnowledgeBaseEntity buildKnowledgeBaseEntity(Long id) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setName("Java 面试题库");
        entity.setCategory(CATEGORY);
        entity.setFileSize(FILE.getSize());
        entity.setStorageKey(STORAGE_KEY);
        entity.setStorageUrl(STORAGE_URL);
        entity.setFileHash(FILE_HASH);
        entity.setVectorStatus(VectorStatus.PENDING);
        entity.setOriginalFilename(FILE.getOriginalFilename());
        return entity;
    }
}
