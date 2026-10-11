package com.janemichael.jmrecruitingcrm.search;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/searches")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public List<SearchResponse> list(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) SearchEnums.Status status,
            @RequestParam(defaultValue = "false") boolean open
    ) {
        return searchService.list(companyId, status, open);
    }

    @GetMapping("/{id}")
    public SearchResponse get(@PathVariable Long id) {
        return searchService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SearchResponse create(@Valid @RequestBody SearchRequest request) {
        return searchService.create(request);
    }

    @PutMapping("/{id}")
    public SearchResponse update(@PathVariable Long id, @Valid @RequestBody SearchRequest request) {
        return searchService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        searchService.delete(id);
    }
}
