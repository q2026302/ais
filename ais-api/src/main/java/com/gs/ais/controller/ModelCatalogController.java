package com.gs.ais.controller;

import com.gs.ais.dto.catalog.GrsaiModelCatalogItem;
import com.gs.ais.service.GrsaiModelCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/model-catalogs/grsai")
public class ModelCatalogController {

    private final GrsaiModelCatalogService catalogService;

    public ModelCatalogController(GrsaiModelCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public ResponseEntity<List<GrsaiModelCatalogItem>> list() {
        return ResponseEntity.ok(catalogService.getCatalog());
    }

    @PostMapping("/refresh")
    public ResponseEntity<List<GrsaiModelCatalogItem>> refresh() {
        return ResponseEntity.ok(catalogService.refresh());
    }
}
