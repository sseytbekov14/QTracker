package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.PermissionService;
import com.kpmg.qtracker.service.RelatedControlList;
import com.kpmg.qtracker.service.RelatedControls;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The controls Other Related Controls offers on the Details tab of a control (related-controls.js): every other
 * control, by Control ID. SoQM Admin Team only, who alone fill the field (AccessPolicy.choosesRelatedControls);
 * the links themselves are saved with Details (POST /api/control-details, relatedControlIds).
 */
@RestController
@RequiredArgsConstructor
public class RelatedControlsController {

    private final PermissionService permissionService;
    private final RelatedControls relatedControls;
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

    @GetMapping("/api/controls/{id}/related-options")
    public ResponseEntity<List<RelatedControls.Option>> options(@PathVariable Long id, HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        permissionService.requireReadable(id, currentUser);
        if (!AccessPolicy.choosesRelatedControls(AccessPolicy.Subject.of(currentUser))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(relatedControls.options(id));
    }
}
