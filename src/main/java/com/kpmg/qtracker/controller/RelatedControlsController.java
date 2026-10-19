package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.RelatedControlList;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The list Other Related Controls is chosen from on the Details tab (multi-value.js): one list for every control
 * (related_control_ref, filled by --import-related-controls). The Control IDs chosen are saved with Details
 * (POST /api/control-details, otherRelatedControls).
 */
@RestController
@RequiredArgsConstructor
public class RelatedControlsController {

    private final RelatedControlList relatedControlList;

    /**
     * The active Control IDs of the list Other Related Controls is chosen from (related_control_ref) that hold q, in any
     * case, by Control ID; at most limit (RelatedControlList.DEFAULT_LIMIT, up to MAX_LIMIT). The same list for every
     * control. Only for those who fill the field (AccessPolicy.choosesRelatedControls); 403 for anyone else.
     */
    @GetMapping("/api/related-controls")
    public ResponseEntity<List<String>> list(@RequestParam(name = "q", required = false) String query,
                                             @RequestParam(name = "limit", required = false) Integer limit,
                                             HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!AccessPolicy.choosesRelatedControls(AccessPolicy.Subject.of(currentUser))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(relatedControlList.search(query, limit));
    }
}
