package com.janemichael.jmrecruitingcrm.trash;

import com.janemichael.jmrecruitingcrm.trash.TrashService.Restored;
import com.janemichael.jmrecruitingcrm.trash.TrashService.TrashItemResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Deleted records, kept for 30 days, and putting them back. */
@RestController
@RequestMapping("/api/trash")
public class TrashController {

    private final TrashService trash;

    public TrashController(TrashService trash) {
        this.trash = trash;
    }

    @GetMapping
    public List<TrashItemResponse> list() {
        return trash.list();
    }

    @PostMapping("/{id}/restore")
    public Restored restore(@PathVariable long id) {
        return trash.restore(id);
    }

    /** Undo for a delete just made: puts back the latest delete of that record. */
    @PostMapping("/undo")
    public Restored undo(@RequestParam TrashKind kind, @RequestParam long recordId) {
        return trash.restoreLatest(kind, recordId);
    }
}
