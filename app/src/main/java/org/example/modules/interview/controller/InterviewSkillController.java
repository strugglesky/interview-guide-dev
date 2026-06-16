package org.example.modules.interview.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.example.common.annotation.RateLimit;
import org.example.common.result.Result;
import org.example.modules.interview.skill.InterviewSkillService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/interview/skills")
@RequiredArgsConstructor
public class InterviewSkillController {
    private final InterviewSkillService interviewSkillService;

    @GetMapping
    public Result<List<InterviewSkillService.SkillDTO>> listSkills() {
        return Result.success(interviewSkillService.getAllSkills());
    }

    @GetMapping("/{skillId}")
    public Result<InterviewSkillService.SkillDTO> getSkill(@PathVariable String skillId) {
        return Result.success(interviewSkillService.getSkill(skillId));
    }

    @PostMapping("/parse-jd")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<List<InterviewSkillService.CategoryDTO>> parseJd(@Valid @RequestBody ParseJdRequest request) {
        return Result.success(interviewSkillService.parseJd(request.jdText()));
    }

    public record ParseJdRequest(@NotBlank String jdText) {}
}
