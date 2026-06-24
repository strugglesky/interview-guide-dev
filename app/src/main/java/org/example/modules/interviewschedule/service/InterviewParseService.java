package org.example.modules.interviewschedule.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.common.ai.LlmProviderRegistry;
import org.example.common.ai.PromptSanitizer;
import org.example.common.ai.PromptSecurityConstants;
import org.example.common.exception.BusinessException;
import org.example.common.model.ErrorCode;
import org.example.modules.interviewschedule.model.CreateInterviewRequest;
import org.example.modules.interviewschedule.model.ParseResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的面试邀约解析服务 - 整合规则解析和AI解析
 * Simplified interview schedule parsing service combining rule-based and AI parsing
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewParseService {
    private static final String SOURCE_FEISHU = "feishu";
    private static final String SOURCE_TENCENT = "tencent";
    private static final String SOURCE_ZOOM = "zoom";
    private static final String SOURCE_OTHER = "other";
    private static final String TYPE_VIDEO = "VIDEO";
    private static final String TYPE_ONSITE = "ONSITE";
    private static final String TYPE_PHONE = "PHONE";
    private static final List<String> KNOWN_SOURCES = List.of(
            SOURCE_FEISHU, SOURCE_TENCENT, SOURCE_ZOOM);
    private static final List<DateTimeFormatter> SUPPORTED_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
    );
    private static final Map<String, Integer> CHINESE_NUMBERS = Map.of("一", 1, "二", 2, "三", 3,
            "四", 4, "五", 5, "六", 6, "七", 7, "八", 8, "九", 9, "十", 10);
    private static final Pattern TIME_PATTERN_FEISHU = Pattern.compile(
            "(?:时间|时段)[：:]\\s*(\\d{4}[-/]\\d{2}[-/]\\d{2}\\s+\\d{2}:\\d{2})");
    private static final Pattern LINK_PATTERN_FEISHU =
            Pattern.compile("https://meeting\\.feishu\\.cn/[^\\s\\n]+");
    private static final Pattern COMPANY_PATTERN_FEISHU =
            Pattern.compile("(?:公司|单位|组织)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern POSITION_PATTERN_FEISHU =
            Pattern.compile("(?:岗位|职位|职务)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern ROUND_PATTERN_FEISHU =
            Pattern.compile("第\\s*[一二三四五六七八九十\\d]+\\s*[轮场]");
    private static final Pattern TIME_PATTERN_TENCENT =
            Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})\\s+(\\d{2}:\\d{2})");
    private static final Pattern MEETING_ID_PATTERN_TENCENT =
            Pattern.compile("(?:会议号|ID)[：:]?\\s*(\\d{9,})");
    private static final Pattern PASSWORD_PATTERN_TENCENT = Pattern.compile("密码[：:]?\\s*(\\d{4,})");
    private static final Pattern COMPANY_PATTERN_TENCENT =
            Pattern.compile("(?:公司|单位)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern POSITION_PATTERN_TENCENT =
            Pattern.compile("(?:岗位|职位)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern LINK_PATTERN_ZOOM = Pattern.compile("https://zoom\\.us/j/[^\\s\\n]+");
    private static final Pattern DATE_PATTERN_ZOOM = Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})");
    private static final Pattern HOUR_PATTERN_ZOOM = Pattern.compile("(\\d{1,2}:\\d{2})");
    private static final Pattern ROUND_NUMBER_PATTERN = Pattern.compile("[一二三四五六七八九十]|\\d+");
    private static final Pattern HEADER_PATTERN = Pattern.compile("【([^】]{1,50})】\\s*([^\\n]{1,80})");
    private static final Pattern TIME_PATTERN_GENERIC = Pattern.compile(
            "(\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{1,2}:\\d{2}(?::\\d{2})?)");
    private static final Pattern LINK_PATTERN_GENERIC = Pattern.compile("https?://[^\\s\\n]+");
    private static final Pattern COMPANY_PATTERN_GENERIC =
            Pattern.compile("(?:公司|单位|组织|企业|品牌)[：:]\\s*([^\\n]{1,50})");
    private static final Pattern POSITION_PATTERN_GENERIC =
            Pattern.compile("(?:岗位|职位|职务|面试岗位)[：:]\\s*([^\\n]{1,60})");
    private static final Pattern INTERVIEWER_PATTERN =
            Pattern.compile("(?:面试官|联系人|邀请人)[：:]\\s*([^\\n]{1,30})");
    private static final Pattern NOTES_PATTERN =
            Pattern.compile("(?:备注|说明|提示)[：:]\\s*([^\\n]{1,200})");
    private static final Pattern ROUND_PATTERN_GENERIC = Pattern.compile(
            "(?:第\\s*[一二三四五六七八九十\\d]+\\s*[轮场面]|[一二三四五六七八九十\\d]+面)");
    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "(?:时长|预计|大约|约)\\s*(\\d{1,3})\\s*分钟");
    private static final String PARSE_PROMPT = """
            你是一个专业的面试邀约信息提取助手。请仔细分析以下文本，提取面试相关信息。
            
            **提取规则**：
            1. companyName（公司名称）：提取面试公司的全称或简称，**必需字段**
            2. position（岗位名称）：提取面试岗位的名称，**必需字段**
            3. interviewTime（面试时间）：提取面试开始时间并转换为 ISO 8601 格式，**必需字段**
               - 格式：YYYY-MM-DDTHH:MM:SS（例如：2026-04-10T14:00:00）
               - 若只有相对时间（如"明天下午2点"），根据当前日期 %s 推算
            4. interviewType（面试形式）：ONSITE（现场）/ VIDEO（视频）/ PHONE（电话）
            5. meetingLink（会议链接）：提取完整的会议链接或会议号+密码
            6. roundNumber（第几轮面试）：提取数字（1-10），如"二面"提取为2
            7. notes（其他备注）：包含面试官姓名（如果不重要可忽略）、时长（**默认30分钟**）等。
            
            **重要提示**：
            - 面试官是谁不重要，只需在 notes 中提及。
            - 优先保证 companyName、position、interviewTime 的准确性。
            - 如果文本中没说时长，默认设置为 30 分钟。
            
            **待解析文本**：
            %s
            
            **返回格式**：
            纯 JSON 格式，不要包含```json标记，示例：
            {"companyName":"阿里巴巴","position":"Java工程师","interviewTime":"2026-04-10T14:00:00","interviewType":"VIDEO","meetingLink":"https://meeting.feishu.cn/xxx","roundNumber":2,"interviewer":"张三","notes":"技术面"}
            """;

    private final LlmProviderRegistry llmProviderRegistry;
    private final ObjectMapper objectMapper;
    private final PromptSanitizer promptSanitizer;

    /**
     * 解析面试邀约文本
     *
     * @param rawText 待解析的原始文本
     * @param source 来源平台（feishu/tencent/zoom），若为 null 则自动识别
     * @return 包含解析到的面试信息的 ParseResponse
     */
    public ParseResponse parse(String rawText, String source) {
        try {
            String normalizedText = normalizeRawText(rawText);
            String resolvedSource = resolveSource(source, normalizedText);
            StringBuilder parseLog = buildParseLog(normalizedText, resolvedSource);
            CreateInterviewRequest ruleRequest = parseByRules(normalizedText, resolvedSource, parseLog);
            boolean ruleSuccess = hasRequiredFields(ruleRequest);
            CreateInterviewRequest mergedRequest = ruleSuccess
                    ? ruleRequest
                    : mergeRequests(ruleRequest, parseByAi(normalizedText, parseLog));
            CreateInterviewRequest finalRequest =
                    finalizeRequest(mergedRequest, normalizedText, resolvedSource);
            boolean success = hasRequiredFields(finalRequest);
            appendCompletionLog(parseLog, finalRequest, success, !ruleSuccess);
            return buildParseResponse(finalRequest, success, !ruleSuccess, resolvedSource, parseLog);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析面试邀约失败: source={}, textLength={}",
                    source, rawText != null ? rawText.length() : null, e);
            return buildFailedResponse("解析失败，请手动输入");
        }
    }

    private String normalizeRawText(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "待解析文本不能为空");
        }
        return rawText.strip();
    }

    private String resolveSource(String source, String text) {
        if (StringUtils.hasText(source)) {
            String normalized = source.strip().toLowerCase(Locale.ROOT);
            if (KNOWN_SOURCES.contains(normalized) || SOURCE_OTHER.equals(normalized)) {
                return normalized;
            }
        }
        String lowerCaseText = text.toLowerCase(Locale.ROOT);
        if (lowerCaseText.contains("meeting.feishu.cn") || lowerCaseText.contains("飞书")) {
            return SOURCE_FEISHU;
        }
        if (lowerCaseText.contains("meeting.tencent.com") || lowerCaseText.contains("腾讯会议")) {
            return SOURCE_TENCENT;
        }
        if (lowerCaseText.contains("zoom.us/j/") || lowerCaseText.contains(" zoom ")) {
            return SOURCE_ZOOM;
        }
        return SOURCE_OTHER;
    }

    private StringBuilder buildParseLog(String text, String source) {
        StringBuilder parseLog = new StringBuilder();
        parseLog.append("source=").append(source)
                .append(", textLength=").append(text.length()).append('\n');
        if (promptSanitizer.detectInjectionAttempt(text)) {
            parseLog.append("detectedPromptInjection=true").append('\n');
        }
        return parseLog;
    }

    private CreateInterviewRequest parseByRules(
            String text,
            String source,
            StringBuilder parseLog
    ) {
        CreateInterviewRequest sourceRequest = switch (source) {
            case SOURCE_FEISHU -> parseFeishu(text);
            case SOURCE_TENCENT -> parseTencent(text);
            case SOURCE_ZOOM -> parseZoom(text);
            default -> new CreateInterviewRequest();
        };
        CreateInterviewRequest mergedRequest = mergeRequests(sourceRequest, parseGeneric(text));
        parseLog.append("ruleExtractedRequired=").append(hasRequiredFields(mergedRequest))
                .append(", company=").append(defaultText(mergedRequest.getCompanyName()))
                .append(", position=").append(defaultText(mergedRequest.getPosition()))
                .append(", time=").append(mergedRequest.getInterviewTime()).append('\n');
        return mergedRequest;
    }

    private CreateInterviewRequest parseFeishu(String text) {
        CreateInterviewRequest request = new CreateInterviewRequest();
        request.setCompanyName(normalizeCapturedValue(findFirstGroup(COMPANY_PATTERN_FEISHU, text)));
        request.setPosition(normalizePositionValue(findFirstGroup(POSITION_PATTERN_FEISHU, text)));
        request.setInterviewTime(parseDateTime(findFirstGroup(TIME_PATTERN_FEISHU, text)));
        request.setMeetingLink(normalizeCapturedValue(findFirstGroup(LINK_PATTERN_FEISHU, text)));
        request.setRoundNumber(extractRoundNumber(findFirstGroup(ROUND_PATTERN_FEISHU, text)));
        return request;
    }

    private CreateInterviewRequest parseTencent(String text) {
        CreateInterviewRequest request = new CreateInterviewRequest();
        Matcher matcher = TIME_PATTERN_TENCENT.matcher(text);
        if (matcher.find()) {
            request.setInterviewTime(parseDateTime(matcher.group(1) + " " + matcher.group(2)));
        }
        request.setCompanyName(normalizeCapturedValue(findFirstGroup(COMPANY_PATTERN_TENCENT, text)));
        request.setPosition(normalizePositionValue(findFirstGroup(POSITION_PATTERN_TENCENT, text)));
        request.setMeetingLink(buildTencentMeetingLink(text));
        request.setRoundNumber(extractRoundNumber(findFirstGroup(ROUND_PATTERN_GENERIC, text)));
        return request;
    }

    private CreateInterviewRequest parseZoom(String text) {
        CreateInterviewRequest request = new CreateInterviewRequest();
        String date = findFirstGroup(DATE_PATTERN_ZOOM, text);
        String hour = findFirstGroup(HOUR_PATTERN_ZOOM, text);
        if (StringUtils.hasText(date) && StringUtils.hasText(hour)) {
            request.setInterviewTime(parseDateTime(date + " " + hour));
        }
        request.setMeetingLink(normalizeCapturedValue(findFirstGroup(LINK_PATTERN_ZOOM, text)));
        request.setRoundNumber(extractRoundNumber(findFirstGroup(ROUND_PATTERN_GENERIC, text)));
        return request;
    }

    private CreateInterviewRequest parseGeneric(String text) {
        CreateInterviewRequest request = new CreateInterviewRequest();
        applyHeaderInfo(request, text);
        request.setCompanyName(selectText(
                request.getCompanyName(),
                normalizeCapturedValue(findFirstGroup(COMPANY_PATTERN_GENERIC, text))
        ));
        request.setPosition(selectText(
                request.getPosition(),
                normalizePositionValue(findFirstGroup(POSITION_PATTERN_GENERIC, text))
        ));
        request.setInterviewTime(parseDateTime(findFirstGroup(TIME_PATTERN_GENERIC, text)));
        request.setMeetingLink(normalizeCapturedValue(findFirstGroup(LINK_PATTERN_GENERIC, text)));
        request.setRoundNumber(extractRoundNumber(findFirstGroup(ROUND_PATTERN_GENERIC, text)));
        request.setInterviewer(normalizeCapturedValue(findFirstGroup(INTERVIEWER_PATTERN, text)));
        request.setNotes(buildNotes(findFirstGroup(NOTES_PATTERN, text), request.getInterviewer(), text));
        return request;
    }

    private void applyHeaderInfo(CreateInterviewRequest request, String text) {
        Matcher matcher = HEADER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return;
        }
        request.setCompanyName(normalizeCapturedValue(matcher.group(1)));
        String headerTitle = matcher.group(2);
        String position = headerTitle;
        int invitationIndex = headerTitle.indexOf("邀请");
        if (invitationIndex > 0) {
            position = headerTitle.substring(0, invitationIndex);
        }
        request.setPosition(normalizePositionValue(position));
    }

    private CreateInterviewRequest parseByAi(String text, StringBuilder parseLog) {
        try {
            String wrappedText = buildWrappedText(text);
            String prompt = PARSE_PROMPT.formatted(
                    LocalDate.now(),
                    PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" + wrappedText
            );
            ChatClient chatClient = llmProviderRegistry.getPlainChatClient(null);
            String content = chatClient.prompt().user(prompt).call().content();
            parseLog.append("aiResponse=").append(abbreviate(content)).append('\n');
            return buildRequestFromAiJson(extractJsonObject(content));
        } catch (Exception e) {
            log.error("AI解析面试邀约失败: textLength={}", text.length(), e);
            parseLog.append("aiError=").append(e.getMessage()).append('\n');
            return null;
        }
    }

    private String buildWrappedText(String text) {
        String sanitized = promptSanitizer.sanitize(text);
        return promptSanitizer.wrapWithDelimiters("interview_invite", sanitized);
    }

    private CreateInterviewRequest buildRequestFromAiJson(String json) throws Exception {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        Map<?, ?> payload = objectMapper.readValue(json, Map.class);
        CreateInterviewRequest request = new CreateInterviewRequest();
        request.setCompanyName(normalizeCapturedValue(asText(payload.get("companyName"))));
        request.setPosition(normalizePositionValue(asText(payload.get("position"))));
        request.setInterviewTime(parseDateTime(asText(payload.get("interviewTime"))));
        request.setInterviewType(normalizeInterviewType(asText(payload.get("interviewType"))));
        request.setMeetingLink(normalizeCapturedValue(asText(payload.get("meetingLink"))));
        request.setRoundNumber(extractRoundNumber(asText(payload.get("roundNumber"))));
        request.setInterviewer(normalizeCapturedValue(asText(payload.get("interviewer"))));
        request.setNotes(normalizeCapturedValue(asText(payload.get("notes"))));
        return request;
    }

    private String extractJsonObject(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String normalized = content.replace("```json", "").replace("```", "").strip();
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return normalized;
        }
        return normalized.substring(start, end + 1);
    }

    private CreateInterviewRequest mergeRequests(
            CreateInterviewRequest primary,
            CreateInterviewRequest secondary
    ) {
        CreateInterviewRequest merged = new CreateInterviewRequest();
        merged.setCompanyName(selectText(readCompany(primary), readCompany(secondary)));
        merged.setPosition(selectText(readPosition(primary), readPosition(secondary)));
        merged.setInterviewTime(readTime(primary) != null ? readTime(primary) : readTime(secondary));
        merged.setInterviewType(selectText(readType(primary), readType(secondary)));
        merged.setMeetingLink(selectText(readMeetingLink(primary), readMeetingLink(secondary)));
        merged.setRoundNumber(readRound(primary) != null ? readRound(primary) : readRound(secondary));
        merged.setInterviewer(selectText(readInterviewer(primary), readInterviewer(secondary)));
        merged.setNotes(mergeNotes(readNotes(primary), readNotes(secondary)));
        return merged;
    }

    private CreateInterviewRequest finalizeRequest(
            CreateInterviewRequest request,
            String text,
            String source
    ) {
        CreateInterviewRequest finalRequest = request != null ? request : new CreateInterviewRequest();
        finalRequest.setCompanyName(normalizeCapturedValue(finalRequest.getCompanyName()));
        finalRequest.setPosition(normalizePositionValue(finalRequest.getPosition()));
        finalRequest.setInterviewType(resolveInterviewType(finalRequest, text, source));
        finalRequest.setMeetingLink(normalizeCapturedValue(finalRequest.getMeetingLink()));
        finalRequest.setRoundNumber(resolveRoundNumber(finalRequest.getRoundNumber()));
        finalRequest.setInterviewer(normalizeCapturedValue(finalRequest.getInterviewer()));
        finalRequest.setNotes(buildNotes(finalRequest.getNotes(), finalRequest.getInterviewer(), text));
        return finalRequest;
    }

    private String resolveInterviewType(
            CreateInterviewRequest request,
            String text,
            String source
    ) {
        if (StringUtils.hasText(request.getInterviewType())) {
            return normalizeInterviewType(request.getInterviewType());
        }
        String lowerCaseText = text.toLowerCase(Locale.ROOT);
        if (lowerCaseText.contains("电话")) {
            return TYPE_PHONE;
        }
        if (lowerCaseText.contains("现场") || lowerCaseText.contains("线下")) {
            return TYPE_ONSITE;
        }
        if (KNOWN_SOURCES.contains(source) || lowerCaseText.contains("视频")
                || lowerCaseText.contains("腾讯会议") || lowerCaseText.contains("zoom")) {
            return TYPE_VIDEO;
        }
        return StringUtils.hasText(request.getMeetingLink()) ? TYPE_VIDEO : TYPE_ONSITE;
    }

    private String buildTencentMeetingLink(String text) {
        String directLink = normalizeCapturedValue(findFirstGroup(LINK_PATTERN_GENERIC, text));
        if (StringUtils.hasText(directLink)) {
            return directLink;
        }
        String meetingId = findFirstGroup(MEETING_ID_PATTERN_TENCENT, text);
        String password = findFirstGroup(PASSWORD_PATTERN_TENCENT, text);
        if (!StringUtils.hasText(meetingId)) {
            return null;
        }
        return StringUtils.hasText(password)
                ? "会议号: " + meetingId + "，密码: " + password
                : "会议号: " + meetingId;
    }

    private LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.strip().replace('/', '-');
        for (DateTimeFormatter formatter : SUPPORTED_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
                // try next formatter
            }
        }
        return null;
    }

    private Integer extractRoundNumber(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = ROUND_NUMBER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String token = matcher.group();
        if (token.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(token);
        }
        return CHINESE_NUMBERS.get(token);
    }

    private Integer resolveRoundNumber(Integer roundNumber) {
        if (roundNumber == null || roundNumber <= 0) {
            return 1;
        }
        return Math.min(roundNumber, 10);
    }

    private String buildNotes(String notes, String interviewer, String text) {
        List<String> values = new ArrayList<>();
        if (StringUtils.hasText(notes)) {
            values.add(normalizeCapturedValue(notes));
        }
        String duration = extractDurationNote(text);
        if (StringUtils.hasText(duration) && values.stream().noneMatch(item -> item.contains("分钟"))) {
            values.add(duration);
        }
        if (StringUtils.hasText(interviewer) && values.stream().noneMatch(item -> item.contains(interviewer))) {
            values.add("面试官：" + interviewer);
        }
        if (values.stream().noneMatch(item -> item.contains("30分钟") || item.contains("分钟"))) {
            values.add("默认时长30分钟");
        }
        return String.join("；", values);
    }

    private String extractDurationNote(String text) {
        Matcher matcher = DURATION_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return "时长约" + matcher.group(1) + "分钟";
    }

    private boolean hasRequiredFields(CreateInterviewRequest request) {
        return request != null
                && StringUtils.hasText(request.getCompanyName())
                && StringUtils.hasText(request.getPosition())
                && request.getInterviewTime() != null;
    }

    private ParseResponse buildParseResponse(
            CreateInterviewRequest request,
            boolean success,
            boolean usedAi,
            String source,
            StringBuilder parseLog
    ) {
        ParseResponse response = new ParseResponse();
        response.setSuccess(success);
        response.setData(success ? request : null);
        response.setConfidence(calculateConfidence(request, success, usedAi, source));
        response.setParseMethod(usedAi ? "ai" : "rule");
        response.setLog(parseLog.toString().strip());
        return response;
    }

    private ParseResponse buildFailedResponse(String message) {
        ParseResponse response = new ParseResponse();
        response.setSuccess(false);
        response.setData(null);
        response.setConfidence(0D);
        response.setParseMethod("ai");
        response.setLog(message);
        return response;
    }

    private double calculateConfidence(
            CreateInterviewRequest request,
            boolean success,
            boolean usedAi,
            String source
    ) {
        if (!success) {
            return 0D;
        }
        double confidence = usedAi ? 0.82D : 0.92D;
        if (KNOWN_SOURCES.contains(source)) {
            confidence += 0.03D;
        }
        if (StringUtils.hasText(request.getMeetingLink())) {
            confidence += 0.02D;
        }
        if (StringUtils.hasText(request.getInterviewer())) {
            confidence += 0.01D;
        }
        return Math.min(confidence, 0.99D);
    }

    private void appendCompletionLog(
            StringBuilder parseLog,
            CreateInterviewRequest request,
            boolean success,
            boolean usedAi
    ) {
        List<String> missingFields = new ArrayList<>();
        if (!StringUtils.hasText(readCompany(request))) {
            missingFields.add("companyName");
        }
        if (!StringUtils.hasText(readPosition(request))) {
            missingFields.add("position");
        }
        if (readTime(request) == null) {
            missingFields.add("interviewTime");
        }
        parseLog.append("success=").append(success)
                .append(", parseMethod=").append(usedAi ? "ai" : "rule")
                .append(", missingFields=").append(missingFields).append('\n');
    }

    private String normalizePositionValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = normalizeCapturedValue(value)
                .replaceAll("(第\\s*[一二三四五六七八九十\\d]+\\s*[轮场面]?|[一二三四五六七八九十\\d]+面)", "")
                .replace("面试邀请", "")
                .replace("邀请", "")
                .replace("通知", "")
                .strip();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String normalizeCapturedValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.strip()
                .replace("\r", "")
                .replaceAll("[，。；、]+$", "")
                .replaceAll("\\s{2,}", " ");
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String normalizeInterviewType(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case TYPE_ONSITE, "ONSITE面试", "现场", "线下" -> TYPE_ONSITE;
            case TYPE_PHONE, "电话", "PHONE面试" -> TYPE_PHONE;
            default -> TYPE_VIDEO;
        };
    }

    private String findFirstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
    }

    private String mergeNotes(String primary, String secondary) {
        if (!StringUtils.hasText(primary)) {
            return normalizeCapturedValue(secondary);
        }
        if (!StringUtils.hasText(secondary) || primary.contains(secondary)) {
            return normalizeCapturedValue(primary);
        }
        return normalizeCapturedValue(primary + "；" + secondary);
    }

    private String selectText(String primary, String secondary) {
        return StringUtils.hasText(primary) ? primary : secondary;
    }

    private String defaultText(String value) {
        return StringUtils.hasText(value) ? value : "<empty>";
    }

    private String abbreviate(String content) {
        if (!StringUtils.hasText(content)) {
            return "<empty>";
        }
        return content.length() <= 160 ? content : content.substring(0, 160) + "...";
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private String readCompany(CreateInterviewRequest request) {
        return request != null ? request.getCompanyName() : null;
    }

    private String readPosition(CreateInterviewRequest request) {
        return request != null ? request.getPosition() : null;
    }

    private LocalDateTime readTime(CreateInterviewRequest request) {
        return request != null ? request.getInterviewTime() : null;
    }

    private String readType(CreateInterviewRequest request) {
        return request != null ? request.getInterviewType() : null;
    }

    private String readMeetingLink(CreateInterviewRequest request) {
        return request != null ? request.getMeetingLink() : null;
    }

    private Integer readRound(CreateInterviewRequest request) {
        return request != null ? request.getRoundNumber() : null;
    }

    private String readInterviewer(CreateInterviewRequest request) {
        return request != null ? request.getInterviewer() : null;
    }

    private String readNotes(CreateInterviewRequest request) {
        return request != null ? request.getNotes() : null;
    }

}
