package com.payflow.reconciliation;

import com.payflow.common.ApiException;
import com.payflow.common.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin")
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/runs")
    @Operation(summary = "Run ledger reconciliation now")
    public ReconciliationService.Run run() {
        return reconciliation.run().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "reconciliation_running",
                "a reconciliation run is already in progress"));
    }

    @GetMapping("/runs/latest")
    @Operation(summary = "Latest reconciliation result")
    public ReconciliationService.Run latest() {
        return reconciliation.latest().orElseThrow(() -> new NotFoundException("reconciliation run", "latest"));
    }
}
