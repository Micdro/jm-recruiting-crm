package com.janemichael.jmrecruitingcrm.template;

import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.ActionType;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.template.TemplateDtos.TemplateRequest;
import com.janemichael.jmrecruitingcrm.template.TemplateDtos.TemplateResponse;
import com.janemichael.jmrecruitingcrm.undo.UndoLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** The team's saved messages. Anyone can add or change them; each remembers who wrote it. */
@Service
@Transactional(readOnly = true)
public class TemplateService {

    private final MessageTemplateRepository repository;
    private final CurrentUserService currentUserService;
    private final UndoLog undoLog;

    public TemplateService(MessageTemplateRepository repository, CurrentUserService currentUserService, UndoLog undoLog) {
        this.undoLog = undoLog;
        this.repository = repository;
        this.currentUserService = currentUserService;
    }

    /** By touch (templates for any touch last), then name. */
    public List<TemplateResponse> list() {
        return repository.findAllWithOwner().stream()
                .sorted(Comparator.comparing((MessageTemplate template) ->
                                template.getTouch() == null ? Integer.MAX_VALUE : template.getTouch())
                        .thenComparing(template -> template.getName().toLowerCase()))
                .map(TemplateResponse::from)
                .toList();
    }

    @Transactional
    public TemplateResponse create(TemplateRequest request) {
        MessageTemplate template = new MessageTemplate();
        apply(request, template);
        template.setOwner(currentUserService.get());
        MessageTemplate saved = repository.save(template);
        undoLog.record("Added the template " + saved.getName(), UndoLog.Kind.TEMPLATE, saved.getId());
        return TemplateResponse.from(saved);
    }

    @Transactional
    public TemplateResponse update(Long id, TemplateRequest request) {
        MessageTemplate template = find(id);
        apply(request, template);
        undoLog.record("Edited the template " + template.getName(), UndoLog.Kind.TEMPLATE, id);
        return TemplateResponse.from(template);
    }

    @Transactional
    public void delete(Long id) {
        MessageTemplate template = find(id);
        undoLog.record("Deleted the template " + template.getName(), UndoLog.Kind.TEMPLATE, id);
        repository.delete(template);
    }

    private void apply(TemplateRequest request, MessageTemplate template) {
        if (request.channel() != ActionType.EMAIL && request.channel() != ActionType.LINKEDIN) {
            throw new BadRequestException("Templates are for LinkedIn or email");
        }
        template.setName(request.name().trim());
        template.setChannel(request.channel());
        template.setTouch(request.touch());
        String subject = request.subject() == null ? null : request.subject().trim();
        template.setSubject(request.channel() == ActionType.EMAIL && subject != null && !subject.isEmpty() ? subject : null);
        template.setBody(request.body().strip());
    }

    private MessageTemplate find(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Template not found"));
    }
}
