package com.janemichael.jmrecruitingcrm.placement;

import com.janemichael.jmrecruitingcrm.placement.PlacementDtos.PlacementRequest;
import com.janemichael.jmrecruitingcrm.placement.PlacementDtos.PlacementResponse;
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
@RequestMapping("/api/placements")
public class PlacementController {

    private final PlacementService placementService;

    public PlacementController(PlacementService placementService) {
        this.placementService = placementService;
    }

    @GetMapping
    public List<PlacementResponse> list() {
        return placementService.list();
    }

    @GetMapping("/{id}")
    public PlacementResponse get(@PathVariable Long id) {
        return placementService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlacementResponse create(@Valid @RequestBody PlacementRequest request) {
        return placementService.create(request);
    }

    @PutMapping("/{id}")
    public PlacementResponse update(@PathVariable Long id, @Valid @RequestBody PlacementRequest request) {
        return placementService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        placementService.delete(id);
    }
}
