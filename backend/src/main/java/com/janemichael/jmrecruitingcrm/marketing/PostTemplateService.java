package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostAccount;
import com.janemichael.jmrecruitingcrm.marketing.ContentEnums.PostTheme;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostTemplateRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostTemplateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The team's post templates. Anyone can add, change or delete them; each remembers who made it.
 * Posts made from a template keep their own copy of the text, so changing or deleting a template
 * never changes a post.
 */
@Service
@Transactional(readOnly = true)
public class PostTemplateService {

    private final PostTemplateRepository repository;
    private final CurrentUserService currentUser;

    public PostTemplateService(PostTemplateRepository repository, CurrentUserService currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    /** By name. Names are encrypted, so they're sorted here rather than by the database. */
    public List<PostTemplateResponse> list() {
        return repository.findAllWithOwner().stream()
                .sorted(Comparator.comparing((PostTemplate template) -> template.getName().toLowerCase(Locale.ROOT))
                        .thenComparing(PostTemplate::getId))
                .map(PostTemplateResponse::from)
                .toList();
    }

    @Transactional
    public PostTemplateResponse create(PostTemplateRequest request) {
        PostTemplate template = new PostTemplate();
        apply(request, template);
        template.setOwner(currentUser.get());
        return PostTemplateResponse.from(repository.save(template));
    }

    @Transactional
    public PostTemplateResponse update(Long id, PostTemplateRequest request) {
        PostTemplate template = find(id);
        apply(request, template);
        return PostTemplateResponse.from(template);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(find(id));
    }

    private void apply(PostTemplateRequest request, PostTemplate template) {
        String name = Text.clean(request.name());
        String title = Text.clean(request.title());
        String body = request.body() == null || request.body().isBlank() ? null : request.body().strip();
        String notes = request.notes() == null || request.notes().isBlank() ? null : request.notes().strip();
        if (title == null && body == null && notes == null) {
            throw new BadRequestException("Add a title, notes or the post's text to the template");
        }
        boolean taken = repository.findAll().stream()
                .filter(other -> !Objects.equals(other.getId(), template.getId()))
                .anyMatch(other -> other.getName().equalsIgnoreCase(name));
        if (taken) {
            throw new BadRequestException("There's already a template called " + name + ". Pick another name.");
        }
        template.setName(name);
        template.setTitle(title);
        template.setBody(body);
        template.setNotes(notes);
        template.setTheme(request.theme() == null ? PostTheme.OTHER : request.theme());
        template.setAccount(request.account() == null ? PostAccount.PERSONAL : request.account());
    }

    private PostTemplate find(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Template not found"));
    }
}
