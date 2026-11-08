package com.fatoora.api.flow;

import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import org.springframework.web.bind.annotation.*;

/** The relationship map of any business document. */
@RestController
@RequestMapping("/api/v1/relations")
public class RelationsController {

    private final DocumentGraph graph;

    public RelationsController(DocumentGraph graph) {
        this.graph = graph;
    }

    /** {@code type}: quotation, sales-order, delivery-note, invoice, receipt, purchase-order, goods-receipt, … */
    @GetMapping("/{type}/{id}")
    DocumentGraph.Graph relations(@PathVariable String type, @PathVariable Long id) {
        DocumentGraph.Type t;
        try {
            t = DocumentGraph.Type.valueOf(type.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown_type", "Unknown document type " + type);
        }
        return graph.walk(CurrentUser.orgId(), t, id);
    }
}
