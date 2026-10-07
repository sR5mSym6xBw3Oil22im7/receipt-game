package com.example.receipt.controller;

import com.example.receipt.dto.ReceiptUploadResponse;
import com.example.receipt.dto.ReceiptSaveRequest;
import com.example.receipt.dto.ReceiptText;
import com.example.receipt.dto.ReceiptDetail;
import com.example.receipt.dto.ReceiptSummary;
import com.example.receipt.repository.ReceiptTableRepository;
import com.example.receipt.service.ReceiptService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {
    private final ReceiptService receiptService;
    private final ReceiptTableRepository receiptTableRepository;

    public ReceiptController(ReceiptService receiptService, ReceiptTableRepository receiptTableRepository) {
        this.receiptService = receiptService;
        this.receiptTableRepository = receiptTableRepository;
    }

    @GetMapping
    public ResponseEntity<List<ReceiptSummary>> list() {
        return ResponseEntity.ok(receiptTableRepository.findAllReceiptTables());
    }

    @GetMapping("/{tableName}")
    public ResponseEntity<ReceiptDetail> detail(@PathVariable String tableName) {
        return ResponseEntity.ok(receiptTableRepository.findReceipt(tableName));
    }

    @DeleteMapping("/{tableName}")
    public ResponseEntity<Void> delete(@PathVariable String tableName) {
        receiptTableRepository.deleteReceipt(tableName);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReceiptText> analyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam("geminiApiKey") String geminiApiKey) throws IOException {
        return ResponseEntity.ok(receiptService.analyze(file, geminiApiKey));
    }

    @PostMapping(value = "/save", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReceiptUploadResponse> save(
            @RequestBody ReceiptSaveRequest request) {
        return ResponseEntity.ok(receiptService.store(request.lines(), request.sha256(), request.structuredData()));
    }
}
