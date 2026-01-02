package org.example.controller;

import org.example.SqlService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sql")
public class SqlController {

    @GetMapping("/course-schedule")
    public ResponseEntity<?> courseSchedule(@RequestParam(value = "course_code", required = false) String courseCode,
            @RequestParam(value = "title", required = false) String title) {
        try {
            SqlService sql = new SqlService(System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASS"));

            String code = courseCode;
            if ((code == null || code.isBlank()) && title != null && !title.isBlank()) {
                var found = sql.findCourseCodeByTitle(title);
                if (found.isEmpty()) {
                    return ResponseEntity.ok(Map.of("found", false));
                }
                code = found.get();
            }
            if (code == null || code.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "provide course_code or title"));
            }

            var rangeOpt = sql.queryCourseDateRange(code);
            if (rangeOpt.isEmpty()) {
                return ResponseEntity.ok(Map.of("found", false, "course_code", code));
            }
            Map<String, String> range = rangeOpt.get();
            return ResponseEntity.ok(Map.of("found", true, "course_code", code, "range", range));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
