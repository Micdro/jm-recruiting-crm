package com.janemichael.jmrecruitingcrm.undo;

import com.janemichael.jmrecruitingcrm.undo.UndoLog.Action;
import com.janemichael.jmrecruitingcrm.undo.UndoLog.Undone;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The Recent changes page: your last actions on this computer, and undoing one. */
@RestController
@RequestMapping("/api/recent-changes")
public class UndoController {

    private final UndoLog undo;

    public UndoController(UndoLog undo) {
        this.undo = undo;
    }

    @GetMapping
    public List<Action> list() {
        return undo.list();
    }

    @PostMapping("/{id}/undo")
    public Undone undo(@PathVariable long id) {
        return undo.undo(id);
    }
}
