package com.janemichael.jmrecruitingcrm.signals;

import com.janemichael.jmrecruitingcrm.signals.SignalDtos.BoardRequest;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.CompanyBoard;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.JobStarted;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.SignalsResponse;
import com.janemichael.jmrecruitingcrm.signals.SignalDtos.Summary;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The Hiring signals page, the card on Today, and each company's job board. */
@RestController
@RequestMapping("/api/signals")
public class SignalController {

    private final SignalService signals;

    public SignalController(SignalService signals) {
        this.signals = signals;
    }

    @GetMapping
    public SignalsResponse list() {
        return signals.list();
    }

    @GetMapping("/summary")
    public Summary summary() {
        return signals.summary();
    }

    /** Reads the boards now: all of them, or (by default) those not read in the last few hours. */
    @PostMapping("/check")
    public JobStarted check(@RequestParam(defaultValue = "true") boolean all) {
        return signals.startCheck(all);
    }

    /** Looks for the boards of companies that have none on file. */
    @PostMapping("/find-boards")
    public JobStarted findBoards() {
        return signals.startFind();
    }

    @GetMapping("/companies/{id}")
    public CompanyBoard companyBoard(@PathVariable Long id) {
        return signals.companyBoard(id);
    }

    @PutMapping("/companies/{id}/board")
    public CompanyBoard setBoard(@PathVariable Long id, @Valid @RequestBody BoardRequest request) {
        return signals.setBoard(id, request);
    }

    @PostMapping("/companies/{id}/confirm")
    public CompanyBoard confirm(@PathVariable Long id) {
        return signals.confirm(id);
    }

    @PostMapping("/companies/{id}/check")
    public CompanyBoard checkCompany(@PathVariable Long id) {
        return signals.checkNow(id);
    }

    @PostMapping("/companies/{id}/reviewed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reviewed(@PathVariable Long id) {
        signals.markReviewed(id);
    }
}
