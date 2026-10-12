package com.janemichael.jmrecruitingcrm.template;

import com.janemichael.jmrecruitingcrm.template.TemplateDtos.TemplateRequest;
import com.janemichael.jmrecruitingcrm.template.TemplateDtos.TemplateResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public List<TemplateResponse> list() {
        return templateService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@Valid @RequestBody TemplateRequest request) {
        return templateService.create(request);
    }

    @PutMapping("/{id}")
    public TemplateResponse update(@PathVariable Long id, @Valid @RequestBody TemplateRequest request) {
        return templateService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        templateService.delete(id);
    }
}
