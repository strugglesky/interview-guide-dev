package org.example.modules.interview.skill;

public class InterviewSkillService {

    /**
     * JD 解析返回分类（可携带 LLM 匹配的 ref/shared 信息，后端会按本地 categoryRefIndex 纠正）
     */
    public record CategoryDTO(String key, String label, String priority,
                              String ref, Boolean shared) {}
}
