package org.example.infrastructure.file;

import lombok.extern.slf4j.Slf4j;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文件哈希服务。
 * 统一提供文件哈希计算能力，用于文件去重。
 */
@Service
@Slf4j
public class FileHashService {
  private static final String HASH_ALGORITHM = "SHA-256";
  private static final int BUFFER_SIZE = 8192;

  /**
   * 计算上传文件的 SHA-256 哈希值。
   *
   * @param file 上传文件
   * @return 十六进制小写哈希值
   */
  public String calculateHash(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      log.warn("文件不能为空");
      throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
    }

    try (InputStream inputStream = file.getInputStream()) {
      return calculateHash(inputStream);
    } catch (IOException e) {
      log.error("文件哈希计算失败:{}", e.getMessage());
      throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件哈希计算失败", e);
    }
  }

  /**
   * 计算字节数组的 SHA-256 哈希值。
   *
   * @param content 文件字节内容
   * @return 十六进制小写哈希值
   */
  public String calculateHash(byte[] content) {
    if (content == null || content.length == 0) {
      log.warn("文件内容不能为空");
      throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容不能为空");
    }

    MessageDigest digest = createDigest();
    return HexFormat.of().formatHex(digest.digest(content));
  }

  /**
   * 计算输入流的 SHA-256 哈希值。
   *
   * @param inputStream 文件输入流
   * @return 十六进制小写哈希值
   */
  public String calculateHash(InputStream inputStream) {
    if (inputStream == null) {
      log.warn("文件输入流不能为空");
      throw new BusinessException(ErrorCode.BAD_REQUEST, "文件输入流不能为空");
    }

    try {
      MessageDigest digest = createDigest();
      byte[] buffer = new byte[BUFFER_SIZE];
      int bytesRead;
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        digest.update(buffer, 0, bytesRead);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException e) {
      log.error("文件哈希计算失败:{}", e.getMessage());
      throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件哈希计算失败", e);
    }
  }

  /**
   * 判断两个文件哈希值是否一致。
   *
   * @param sourceHash 源文件哈希值
   * @param targetHash 目标文件哈希值
   * @return 哈希值非空且忽略大小写一致时返回 true
   */
  public boolean matches(String sourceHash, String targetHash) {
    return StringUtils.hasText(sourceHash)
        && StringUtils.hasText(targetHash)
        && sourceHash.equalsIgnoreCase(targetHash);
  }

  /**
   * 创建 SHA-256 消息摘要实例。
   *
   * @return 消息摘要实例
   */
  private MessageDigest createDigest() {
    try {
      return MessageDigest.getInstance(HASH_ALGORITHM);
    } catch (NoSuchAlgorithmException e) {
      log.error("哈希算法不可用:{}", e.getMessage());
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "哈希算法不可用", e);
    }
  }
}
