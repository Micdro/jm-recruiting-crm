package com.janemichael.jmrecruitingcrm.contact;

import com.janemichael.jmrecruitingcrm.activity.LogActivityRequest;
import com.janemichael.jmrecruitingcrm.common.Later;
import com.janemichael.jmrecruitingcrm.contact.ContactService.FillQueueResponse;
import com.janemichael.jmrecruitingcrm.common.BulkResult;
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
@RequestMapping("/api/contacts")
public class ContactController {

    private final ContactService contactService;
    private final ContactBulkService bulkService;

    public ContactController(ContactService contactService, ContactBulkService bulkService) {
        this.contactService = contactService;
        this.bulkService = bulkService;
    }

    @GetMapping
    public List<ContactResponse> list(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) OutreachStatus status,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(defaultValue = "false") boolean due,
            @RequestParam(required = false) String q
    ) {
        return contactService.list(companyId, status, ownerId, due, q);
    }

    @GetMapping("/{id}")
    public ContactResponse get(@PathVariable Long id) {
        return contactService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContactResponse create(@Valid @RequestBody ContactRequest request) {
        return contactService.create(request);
    }

    @PutMapping("/{id}")
    public ContactResponse update(@PathVariable Long id, @Valid @RequestBody ContactRequest request) {
        return contactService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        contactService.delete(id);
    }

    /** Logs a touch, reply, meeting or note and returns the updated contact. */
    @PostMapping("/{id}/activities")
    public ContactResponse logActivity(@PathVariable Long id, @Valid @RequestBody LogActivityRequest request) {
        return contactService.logActivity(id, request);
    }

    @PostMapping("/{id}/restart-sequence")
    public ContactResponse restartSequence(@PathVariable Long id) {
        return contactService.restartSequence(id);
    }

    /** Moves the next action to the next business day or a week out. */
    @PostMapping("/{id}/snooze")
    public ContactResponse snooze(@PathVariable Long id, @Valid @RequestBody Later.Request request) {
        return contactService.snooze(id, request.until());
    }

    /** Undoes your last touch, reply, note or snooze on this contact from the last few minutes. */
    @PostMapping("/{id}/undo")
    public ContactResponse undo(@PathVariable Long id) {
        return contactService.undo(id);
    }

    @PostMapping("/fill-queue")
    public FillQueueResponse fillQueue(@RequestBody FillQueueRequest request) {
        return contactService.fillQueue(request.count() == null ? 0 : request.count());
    }

    public record FillQueueRequest(Integer count) {
    }

    /** Changes, schedules or deletes many contacts at once (the bulk actions on the list). */
    @PostMapping("/bulk")
    public BulkResult bulk(@Valid @RequestBody ContactBulkService.Request request) {
        return bulkService.apply(request);
    }

    /** Undoes a bulk action from the last few minutes. */
    @PostMapping("/bulk/{undoId}/undo")
    public BulkResult undoBulk(@PathVariable long undoId) {
        return bulkService.undo(undoId);
    }
}
