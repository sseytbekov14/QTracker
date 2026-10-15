package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.AccessPolicy;
import com.kpmg.qtracker.service.PermissionService;
import com.kpmg.qtracker.service.RelatedControls;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The controls Other Related Controls offers on the Details tab of a control (related-controls.js): every other
 * control, by Control ID. SoQM Admin Team only, who alone see and fill the field (AccessPolicy.seesSoqmOnlyDetails);
 * the links themselves are saved with Details (POST /api/control-details, relatedControlIds).
 */
@RestController
@RequiredArgsConstructor
public class RelatedControlsController {

    private final PermissionService permissionService;
    private final RelatedControls relatedControls;

    @GetMapping("/api/controls/{id}/related-options")
    public ResponseEntity<List<RelatedControls.Option>> options(@PathVariable Long id, HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        permissionService.requireReadable(id, currentUser);
        if (!AccessPolicy.seesSoqmOnlyDetails(AccessPolicy.Subject.of(currentUser))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(relatedControls.options(id));
    }
}
