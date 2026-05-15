package org.example.infrastructure.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 通用文档解析服务
 * 使用 Apache Tika Parser 解析多种文档格式，提取正文文本
 * 供知识库和简历模块共同使用
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentParseService {
    private static final int UNLIMITED_TEXT_LENGTH = -1;
    private static final EmbeddedDocumentExtractor NO_OP_EMBEDDED_EXTRACTOR =
            new EmbeddedDocumentExtractor() {
                @Override
                public boolean shouldParseEmbedded(Metadata metadata) {
                    return false;
                }

                @Override
                public void parseEmbedded(
                        InputStream stream,
                        ContentHandler handler,
                        Metadata metadata,
                        boolean outputHtml
                ) {
                    // Intentionally ignore embedded resources.
                }
            };

    private final TextCleaningService textCleaningService;

    /**
     * 解析 MultipartFile 文档并返回清洗后的文本。
     *
     * @param file 上传文件
     * @return 解析并清洗后的文本内容
     */
    public String parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }

        try (InputStream inputStream = file.getInputStream()) {
            return parseInternal(inputStream, file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            log.error("Read document stream failed: fileName={}", file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文档解析失败", e);
        }
    }

    /**
     * 解析字节数组文档并返回清洗后的文本。
     *
     * @param content 文件字节内容
     * @param fileName 文件名
     * @return 解析并清洗后的文本内容
     */
    public String parse(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容不能为空");
        }

        try (InputStream inputStream = new ByteArrayInputStream(content)) {
            return parseInternal(inputStream, fileName, null);
        } catch (IOException e) {
            log.error("Close document stream failed: fileName={}", fileName, e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文档解析失败", e);
        }
    }

    /**
     * 解析输入流文档并返回清洗后的文本。
     *
     * @param inputStream 文件输入流
     * @param fileName 文件名
     * @return 解析并清洗后的文本内容
     */
    public String parse(InputStream inputStream, String fileName) {
        if (inputStream == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件输入流不能为空");
        }

        return parseInternal(inputStream, fileName, null);
    }

    /**
     * 清洗解析后的原始文本。
     *
     * @param text 原始文本
     * @return 清洗后的文本
     */
    public String cleanText(String text) {
        return textCleaningService.cleanText(text);
    }

    /**
     * 核心解析方法：使用显式 Parser + Context 方式解析文档。
     *
     * @param inputStream 文件输入流
     * @param fileName 文件名
     * @param contentType 文件 MIME 类型
     * @return 解析并清洗后的文本内容
     */
    private String parseInternal(InputStream inputStream, String fileName, String contentType) {
        Metadata metadata = buildMetadata(fileName, contentType);
        BodyContentHandler handler = new BodyContentHandler(UNLIMITED_TEXT_LENGTH);
        Parser parser = createParser();
        ParseContext parseContext = buildParseContext(parser);

        try {
            parser.parse(inputStream, handler, metadata, parseContext);
            return cleanText(handler.toString());
        } catch (IOException | SAXException | TikaException e) {
            log.error("Document parse failed: fileName={}, contentType={}", fileName, contentType, e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文档解析失败", e);
        }
    }

    /**
     * 创建自动识别文档类型的解析器。
     *
     * @return 文档解析器
     */
    private Parser createParser() {
        return new AutoDetectParser();
    }

    /**
     * 构建解析上下文并注入 Parser、嵌入资源策略和 PDF 配置。
     *
     * @param parser 文档解析器
     * @return 解析上下文
     */
    private ParseContext buildParseContext(Parser parser) {
        ParseContext parseContext = new ParseContext();
        parseContext.set(Parser.class, parser);
        parseContext.set(EmbeddedDocumentExtractor.class, NO_OP_EMBEDDED_EXTRACTOR);
        parseContext.set(PDFParserConfig.class, buildPdfParserConfig());
        return parseContext;
    }

    /**
     * 构建 PDF 解析配置，关闭图片、注释和嵌入内容提取。
     *
     * @return PDF 解析配置
     */
    private PDFParserConfig buildPdfParserConfig() {
        PDFParserConfig config = new PDFParserConfig();
        config.setExtractInlineImages(false);
        config.setExtractAnnotationText(false);
        config.setExtractBookmarksText(false);
        config.setExtractAcroFormContent(false);
        return config;
    }

    /**
     * 构建文档元数据。
     *
     * @param fileName 文件名
     * @param contentType 文件 MIME 类型
     * @return 文档元数据
     */
    private Metadata buildMetadata(String fileName, String contentType) {
        Metadata metadata = new Metadata();
        if (fileName != null && !fileName.isBlank()) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        }
        if (contentType != null && !contentType.isBlank()) {
            metadata.set(Metadata.CONTENT_TYPE, contentType);
        }
        return metadata;
    }
}
